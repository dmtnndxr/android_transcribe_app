//! Small audio helpers shared between the engine and the subtitle pipeline.

/// Centre of the quietest 100 ms window in `samples[from..to]`; used to pick a
/// natural split point when audio must be cut mid-speech.
pub fn find_quietest_split(samples: &[f32], from: usize, to: usize) -> usize {
    const WIN: usize = 1_600; // 100 ms
    if from + WIN > to {
        return to;
    }
    let mut best_pos = to;
    let mut best_energy = f32::MAX;
    let mut i = from;
    while i + WIN <= to {
        let energy: f32 = samples[i..i + WIN].iter().map(|&x| x * x).sum();
        if energy < best_energy {
            best_energy = energy;
            best_pos = i + WIN / 2;
        }
        i += WIN / 2;
    }
    best_pos
}

const WAV_HEADER_LEN: u64 = 44;

/// A mono 16-bit WAV that is written while recording. The sizes in the header
/// are refreshed every second, so the file stays playable if the process dies.
pub struct WavWriter {
    file: std::fs::File,
    sample_rate: u32,
    samples: u64,
    in_header: u64,
}

impl WavWriter {
    pub fn create(path: &str, sample_rate: u32) -> std::io::Result<WavWriter> {
        let file = std::fs::File::create(path)?;
        let mut writer = WavWriter { file, sample_rate, samples: 0, in_header: 0 };
        writer.write_header()?;
        Ok(writer)
    }

    pub fn write(&mut self, samples: &[f32]) -> std::io::Result<()> {
        use std::io::Write;
        let mut bytes = Vec::with_capacity(samples.len() * 2);
        for &s in samples {
            bytes.extend_from_slice(&((s.clamp(-1.0, 1.0) * i16::MAX as f32) as i16).to_le_bytes());
        }
        self.file.write_all(&bytes)?;
        self.samples += samples.len() as u64;
        if self.samples - self.in_header >= u64::from(self.sample_rate) {
            self.write_header()?;
        }
        Ok(())
    }

    fn write_header(&mut self) -> std::io::Result<()> {
        use std::io::{Seek, SeekFrom, Write};
        let data_len = (self.samples * 2).min(u64::from(u32::MAX - 36)) as u32;
        let mut h = Vec::with_capacity(WAV_HEADER_LEN as usize);
        h.extend_from_slice(b"RIFF");
        h.extend_from_slice(&(36 + data_len).to_le_bytes());
        h.extend_from_slice(b"WAVEfmt ");
        h.extend_from_slice(&16u32.to_le_bytes()); // fmt chunk size
        h.extend_from_slice(&1u16.to_le_bytes()); // PCM
        h.extend_from_slice(&1u16.to_le_bytes()); // mono
        h.extend_from_slice(&self.sample_rate.to_le_bytes());
        h.extend_from_slice(&(self.sample_rate * 2).to_le_bytes()); // byte rate
        h.extend_from_slice(&2u16.to_le_bytes()); // block align
        h.extend_from_slice(&16u16.to_le_bytes()); // bits per sample
        h.extend_from_slice(b"data");
        h.extend_from_slice(&data_len.to_le_bytes());
        self.file.seek(SeekFrom::Start(0))?;
        self.file.write_all(&h)?;
        self.file.seek(SeekFrom::Start(WAV_HEADER_LEN + self.samples * 2))?;
        self.in_header = self.samples;
        Ok(())
    }

    pub fn finish(mut self) -> std::io::Result<()> {
        self.write_header()?;
        self.file.sync_data()
    }
}

/// Consumes mic audio until the sender is dropped: saves it (with `wav`),
/// returns what couldn't be saved (all of it without `wav`), and
/// reports through `event` when the mic delivers pure digital silence, which
/// is what Android feeds an app whose microphone it has muted. Runs on its own
/// thread, so the audio callback never waits on the disk.
pub fn record(
    audio: std::sync::mpsc::Receiver<Vec<f32>>,
    mut wav: Option<WavWriter>,
    sample_rate: u32,
    mut event: impl FnMut(&str),
) -> Vec<f32> {
    let rate = u64::from(sample_rate);
    let mut unsaved = Vec::new();
    let (mut total, mut zero_run, mut muted) = (0u64, 0u64, 0u64);
    while let Ok(chunk) = audio.recv() {
        let saved = match wav.as_mut() {
            Some(writer) => match writer.write(&chunk) {
                Ok(()) => true,
                Err(e) => {
                    event(&format!("saving audio failed: {e}"));
                    // Keep what made it to the disk playable; the rest stays in memory.
                    let _ = wav.take().map(WavWriter::finish);
                    false
                }
            },
            None => false,
        };
        if !saved {
            unsaved.extend_from_slice(&chunk);
        }
        total += chunk.len() as u64;
        if chunk.iter().all(|&s| s == 0.0) {
            let before = zero_run;
            zero_run += chunk.len() as u64;
            if before < rate && zero_run >= rate {
                event(&format!("mic muted at {}s", (total - zero_run) / rate));
            }
        } else {
            if zero_run >= rate {
                muted += zero_run;
                event(&format!("mic back after {}s muted", zero_run / rate));
            }
            zero_run = 0;
        }
    }
    if zero_run >= rate {
        muted += zero_run;
    }
    if let Some(writer) = wav {
        if let Err(e) = writer.finish() {
            event(&format!("closing audio file failed: {e}"));
        }
    }
    event(&format!("audio closed: {}s, {}s muted", total / rate, muted / rate));
    unsaved
}

/// A finished recording, read a piece at a time so that an hour of audio
/// doesn't have to fit in memory: the WAV, then whatever didn't make it there.
pub struct Recording {
    file: Option<std::io::BufReader<std::fs::File>>,
    unsaved: Vec<f32>,
    at: usize,
    total: u64,
}

impl Recording {
    pub fn open(wav: Option<&str>, unsaved: Vec<f32>) -> std::io::Result<Recording> {
        use std::io::{Seek, SeekFrom};
        let mut total = unsaved.len() as u64;
        let mut file = None;
        if let Some(path) = wav {
            match std::fs::File::open(path) {
                Ok(mut f) => {
                    let len = f.metadata()?.len();
                    // The header can lag behind the data, so go by the length.
                    if len > WAV_HEADER_LEN {
                        f.seek(SeekFrom::Start(WAV_HEADER_LEN))?;
                        total += (len - WAV_HEADER_LEN) / 2;
                        file = Some(std::io::BufReader::new(f));
                    }
                }
                // The file couldn't be created; the audio is all in memory.
                Err(_) if !unsaved.is_empty() => {}
                Err(e) => return Err(e),
            }
        }
        Ok(Recording { file, unsaved, at: 0, total })
    }

    pub fn total_samples(&self) -> u64 {
        self.total
    }

    /// Appends up to `max` samples to `out`; returns false once there are no more.
    fn read(&mut self, out: &mut Vec<f32>, max: usize) -> std::io::Result<bool> {
        use std::io::Read;
        if let Some(file) = self.file.as_mut() {
            let mut bytes = Vec::with_capacity(max * 2);
            file.by_ref().take(max as u64 * 2).read_to_end(&mut bytes)?;
            out.extend(
                bytes
                    .chunks_exact(2)
                    .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / i16::MAX as f32),
            );
            if bytes.len() == max * 2 {
                return Ok(true);
            }
            self.file = None;
        }
        let n = max.min(self.unsaved.len() - self.at);
        out.extend_from_slice(&self.unsaved[self.at..self.at + n]);
        self.at += n;
        Ok(self.at < self.unsaved.len())
    }

    /// Hands the recording to `each` in runs of at most `max_run` samples, cut
    /// at a quiet point within the last `search` of them. `each` gets the run,
    /// whether it is the first and the last, and the samples done after it;
    /// returning false stops.
    pub fn for_each_run(
        &mut self,
        max_run: usize,
        search: usize,
        mut each: impl FnMut(Vec<f32>, bool, bool, u64) -> bool,
    ) -> std::io::Result<()> {
        let mut pending = Vec::new();
        let (mut more, mut first, mut done) = (true, true, 0u64);
        loop {
            while more && pending.len() <= max_run {
                let want = max_run + 1 - pending.len();
                more = self.read(&mut pending, want)?;
            }
            if pending.is_empty() {
                return Ok(());
            }
            let len = if pending.len() <= max_run {
                pending.len()
            } else {
                find_quietest_split(&pending, max_run - search, max_run)
            };
            let run: Vec<f32> = pending.drain(..len).collect();
            done += len as u64;
            let last = !more && pending.is_empty();
            if !each(run, first, last, done) || last {
                return Ok(());
            }
            first = false;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> String {
        let p = std::env::temp_dir().join(format!("boice-{}-{name}.wav", std::process::id()));
        p.to_string_lossy().into_owned()
    }

    fn data_len(path: &str) -> u32 {
        u32::from_le_bytes(std::fs::read(path).unwrap()[40..44].try_into().unwrap())
    }

    #[test]
    fn playable_while_still_recording() {
        let path = temp("live");
        let mut w = WavWriter::create(&path, 16_000).unwrap();
        for _ in 0..250 {
            w.write(&[0.25; 160]).unwrap();
        }
        // 2.5 s written; a process killed here leaves at least 2 s in the header.
        assert_eq!(data_len(&path), 64_000);
        assert_eq!(std::fs::metadata(&path).unwrap().len(), 44 + 80_000);
        w.finish().unwrap();
        assert_eq!(data_len(&path), 80_000);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn saves_audio_and_reports_muting() {
        let path = temp("muted");
        let (tx, rx) = std::sync::mpsc::channel();
        for (value, chunks) in [(0.1, 100), (0.0, 300), (0.1, 100), (0.0, 50), (0.1, 50)] {
            for _ in 0..chunks {
                tx.send(vec![value; 160]).unwrap();
            }
        }
        drop(tx);
        let mut events = Vec::new();
        let wav = WavWriter::create(&path, 16_000).unwrap();
        assert!(record(rx, Some(wav), 16_000, |e| events.push(e.to_string())).is_empty());
        // The half-second pause is not reported.
        assert_eq!(events, ["mic muted at 1s", "mic back after 3s muted", "audio closed: 6s, 3s muted"]);
        assert_eq!(data_len(&path), 6 * 32_000);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn without_a_file_everything_is_returned() {
        let (tx, rx) = std::sync::mpsc::channel();
        tx.send(vec![0.5; 160]).unwrap();
        drop(tx);
        assert_eq!(record(rx, None, 16_000, |_| {}), vec![0.5; 160]);
    }

    #[test]
    fn reads_back_every_sample_in_runs() {
        let path = temp("runs");
        let mut w = WavWriter::create(&path, 16_000).unwrap();
        // Dropped without finish(), like a killed process: the header lags behind.
        w.write(&[0.25; 230_000]).unwrap();
        w.write(&[0.25; 5_000]).unwrap();
        drop(w);
        let mut rec = Recording::open(Some(&path), vec![0.5; 20_000]).unwrap();
        assert_eq!(rec.total_samples(), 255_000);
        let (mut all, mut flags) = (Vec::new(), Vec::new());
        rec.for_each_run(100_000, 20_000, |run, first, last, done| {
            assert!(run.len() <= 100_000);
            all.extend(run);
            assert_eq!(done, all.len() as u64);
            flags.push((first, last));
            true
        })
        .unwrap();
        assert_eq!(all.len(), 255_000);
        assert!((all[0] - 0.25).abs() < 0.001);
        assert_eq!(all[254_999], 0.5);
        assert_eq!(flags.first(), Some(&(true, false)));
        assert_eq!(flags.iter().filter(|f| f.1).count(), 1);
        assert_eq!(flags.last().map(|f| f.1), Some(true));
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn exactly_one_run_is_first_and_last() {
        let path = temp("exact");
        let mut w = WavWriter::create(&path, 16_000).unwrap();
        w.write(&[0.25; 100_000]).unwrap();
        w.finish().unwrap();
        let mut runs = Vec::new();
        Recording::open(Some(&path), Vec::new())
            .unwrap()
            .for_each_run(100_000, 20_000, |run, first, last, _| {
                runs.push((run.len(), first, last));
                true
            })
            .unwrap();
        assert_eq!(runs, [(100_000, true, true)]);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn can_stop_between_runs() {
        let mut calls = 0;
        Recording::open(None, vec![0.1; 300_000])
            .unwrap()
            .for_each_run(100_000, 20_000, |_, _, _, _| {
                calls += 1;
                false
            })
            .unwrap();
        assert_eq!(calls, 1);
    }
}
