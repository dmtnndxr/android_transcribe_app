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

/// Consumes mic audio until the sender is dropped: saves it (with `wav`) and
/// reports through `event` when the mic delivers pure digital silence, which
/// is what Android feeds an app whose microphone it has muted. Runs on its own
/// thread, so the audio callback never waits on the disk.
pub fn record(
    audio: std::sync::mpsc::Receiver<Vec<f32>>,
    mut wav: Option<WavWriter>,
    sample_rate: u32,
    mut event: impl FnMut(&str),
) {
    let rate = u64::from(sample_rate);
    let (mut total, mut zero_run, mut muted) = (0u64, 0u64, 0u64);
    while let Ok(chunk) = audio.recv() {
        if let Some(writer) = wav.as_mut() {
            if let Err(e) = writer.write(&chunk) {
                event(&format!("saving audio failed: {e}"));
                // Keep what made it to the disk playable.
                let _ = wav.take().map(WavWriter::finish);
            }
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
        record(rx, Some(wav), 16_000, |e| events.push(e.to_string()));
        // The half-second pause is not reported.
        assert_eq!(events, ["mic muted at 1s", "mic back after 3s muted", "audio closed: 6s, 3s muted"]);
        assert_eq!(data_len(&path), 6 * 32_000);
        std::fs::remove_file(path).unwrap();
    }
}
