use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::endpoint::Endpointer;
use crate::engine;

// --- Optional auto-stop endpointing ------------------------------------------
/// Trailing silence after speech that triggers auto-stop.
const AUTO_STOP_SILENCE_MS: u64 = 2000;
/// If no speech is ever detected, auto-stop after this long.
const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

/// A streaming transcription running alongside the recording (streaming
/// models only). Audio reaches it through `VoiceSessionState::stream_tx`.
struct StreamWorker {
    result: Receiver<Result<Option<String>, String>>,
    cancelled: Arc<AtomicBool>,
}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
    /// True while the current recording runs; flipped off on stop/cancel so
    /// the auto-stop monitor (if any) exits.
    pub session_active: Arc<AtomicBool>,
    /// Feeds mic audio to the streaming worker; `None` when not streaming.
    /// Taking the sender out closes the channel, which tells the worker the
    /// recording ended.
    stream_tx: Arc<Mutex<Option<Sender<Vec<f32>>>>>,
    stream_worker: Option<StreamWorker>,
    /// Feeds the recorder thread (see [`crate::audio::record`]). Taking the
    /// sender out finishes the file.
    record_tx: Arc<Mutex<Option<Sender<Vec<f32>>>>>,
    /// Returns the audio that isn't in the file.
    recorder: Option<std::thread::JoinHandle<Vec<f32>>>,
    wav_path: Option<String>,
    /// Stops the transcription of the recording that was stopped last.
    transcription_cancel: Arc<AtomicBool>,
}

impl Drop for VoiceSessionState {
    fn drop(&mut self) {
        // The service went away, possibly mid-recording: close the file.
        self.stream = None;
        self.session_active.store(false, Ordering::SeqCst);
        self.record_tx.lock().unwrap().take();
        abandon_stream(self);
    }
}

/// The text so far, after each run of a recording's transcription.
pub(crate) fn notify_progress(env: &mut JNIEnv, obj: &JObject, text: &str, done: u64, total: u64) {
    if let Ok(jtxt) = env.new_string(text) {
        let millis = |samples: u64| jni::objects::JValue::Long((samples / 16) as i64);
        if env
            .call_method(
                obj,
                "onTranscriptProgress",
                "(Ljava/lang/String;JJ)V",
                &[(&jtxt).into(), millis(done), millis(total)],
            )
            .is_err()
        {
            // The voice panel has no such callback.
            let _ = env.exception_clear();
        }
    }
}

fn notify_canceled(env: &mut JNIEnv, obj: &JObject) {
    if env.call_method(obj, "onTranscriptionCanceled", "()V", &[]).is_err() {
        let _ = env.exception_clear();
    }
}

/// Something for the debug log. Never pass recognized text here.
fn notify_event(env: &mut JNIEnv, obj: &JObject, event: &str) {
    if let Ok(jevent) = env.new_string(event) {
        if env
            .call_method(obj, "onCaptureEvent", "(Ljava/lang/String;)V", &[(&jevent).into()])
            .is_err()
        {
            // The voice panel has no such callback.
            let _ = env.exception_clear();
        }
    }
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

fn notify_level(env: &mut JNIEnv, obj: &JObject, level: f32) {
    let _ = env.call_method(obj, "onAudioLevel", "(F)V", &[level.into()]);
}

/// Live hypothesis while a streaming model transcribes during recording.
fn notify_partial(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        if env
            .call_method(obj, "onPartialText", "(Ljava/lang/String;)V", &[(&jtxt).into()])
            .is_err()
        {
            // Don't leave a pending Java exception on this thread.
            let _ = env.exception_clear();
        }
    }
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

pub fn init_session(env: JNIEnv, target: JObject) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let state = VoiceSessionState {
        stream: None,
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
        session_active: Arc::new(AtomicBool::new(false)),
        stream_tx: Arc::new(Mutex::new(None)),
        stream_worker: None,
        record_tx: Arc::new(Mutex::new(None)),
        recorder: None,
        wav_path: None,
        transcription_cancel: Arc::new(AtomicBool::new(false)),
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    state
}

/// Begin microphone capture. With `auto_stop` set, a monitor thread watches
/// for trailing silence after speech (or a no-speech timeout) and invokes the
/// Java-side `onAutoStop()` callback, which is expected to stop the recording
/// the same way a manual tap would. With `wav_path`, the audio is saved there
/// as it arrives. Returns whether the microphone is capturing.
pub fn start_recording(
    mut env: JNIEnv,
    state: &mut VoiceSessionState,
    auto_stop: bool,
    wav_path: Option<String>,
) -> bool {
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
            );
            return false;
        }
    };

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    // End any previous session's monitor, then arm a fresh flag.
    state.session_active.store(false, Ordering::SeqCst);
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();

    let endpoint = if auto_stop {
        Some(Arc::new(Mutex::new(Endpointer::new())))
    } else {
        None
    };

    abandon_stream(state);
    start_stream(state);

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();
    let endpoint_cb = endpoint.clone();
    let stream_tx = state.stream_tx.clone();
    state.wav_path = wav_path.clone();
    start_recorder(&mut env, state, wav_path);
    let record_tx = state.record_tx.clone();
    let err_jvm = state.jvm.clone();
    let err_target = state.target_ref.clone();

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            if let Some(tx) = record_tx.lock().unwrap().as_ref() {
                let _ = tx.send(data.to_vec());
            }
            if let Some(tx) = stream_tx.lock().unwrap().as_ref() {
                let _ = tx.send(data.to_vec());
            }

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len().max(1) as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            if let Some(ep) = &endpoint_cb {
                ep.lock().unwrap().push(data);
            }

            // throttle updates
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        move |e| {
            log::error!("Stream err: {}", e);
            if let Ok(mut env) = err_jvm.attach_current_thread() {
                notify_event(&mut env, err_target.as_obj(), &format!("mic stream error: {e}"));
            }
        },
        None,
    );

    let stream = stream
        .map_err(|e| e.to_string())
        .and_then(|s| s.play().map(|_| s).map_err(|e| e.to_string()));
    match stream {
        Ok(s) => {
            state.stream = Some(SendStream(s));
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");

            // Settings changed in the app since this process loaded the
            // model: reload while the user speaks rather than after.
            if engine::is_engine_loaded() && !engine::is_current() {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                std::thread::spawn(move || {
                    let _ = engine::ensure_loaded_from_thread(&jvm, &target_ref);
                });
            }

            if let Some(ep) = endpoint {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                let started_at = Instant::now();
                std::thread::spawn(move || loop {
                    std::thread::sleep(Duration::from_millis(100));
                    if !session_active.load(Ordering::SeqCst) {
                        return;
                    }
                    let (speech, silence_ms) = {
                        let ep = ep.lock().unwrap();
                        (ep.speech_started(), ep.silence_ms())
                    };
                    let done = (speech && silence_ms >= AUTO_STOP_SILENCE_MS)
                        || (!speech
                            && started_at.elapsed()
                                >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS));
                    if done {
                        // Claim the session so a simultaneous manual stop and
                        // this monitor can't both fire.
                        if session_active.swap(false, Ordering::SeqCst) {
                            if let Ok(mut env) = jvm.attach_current_thread() {
                                let _ = env.call_method(
                                    target_ref.as_obj(),
                                    "onAutoStop",
                                    "()V",
                                    &[],
                                );
                            }
                        }
                        return;
                    }
                });
            }
            true
        }
        Err(e) => {
            abandon_stream(state);
            state.record_tx.lock().unwrap().take();
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
            false
        }
    }
}

/// Starts a streaming worker if the loaded model supports streaming. Uses
/// `try_lock` so a start never blocks the caller's (UI) thread: an engine
/// still busy with the previous dictation just means this one runs offline.
fn start_stream(state: &mut VoiceSessionState) {
    // With changed settings, transcribe after stop on the reloaded engine.
    if !engine::is_current() {
        return;
    }
    let Some(eng) = engine::get_engine() else { return };
    let streaming = match eng.try_lock() {
        Ok(e) => e.supports_streaming(),
        Err(_) => false,
    };
    if !streaming {
        return;
    }
    let (tx, rx) = mpsc::channel::<Vec<f32>>();
    let (result_tx, result_rx) = mpsc::channel();
    let cancelled = Arc::new(AtomicBool::new(false));
    let worker_cancelled = cancelled.clone();
    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    std::thread::spawn(move || {
        let env = std::cell::RefCell::new(jvm.attach_current_thread().ok());
        let result = engine::transcribe_streaming(
            &eng,
            rx,
            &|| worker_cancelled.load(Ordering::SeqCst),
            &|text| {
                if let Some(env) = env.borrow_mut().as_mut() {
                    notify_partial(env, target_ref.as_obj(), text);
                }
            },
        );
        let _ = result_tx.send(result);
    });
    *state.stream_tx.lock().unwrap() = Some(tx);
    state.stream_worker = Some(StreamWorker {
        result: result_rx,
        cancelled,
    });
}

/// Starts the thread that saves the audio and watches for a muted mic.
fn start_recorder(env: &mut JNIEnv, state: &mut VoiceSessionState, path: Option<String>) {
    // Created right here, so that a cancel can't delete the file before it exists.
    let wav = path.and_then(|p| match crate::audio::WavWriter::create(&p, 16_000) {
        Ok(writer) => Some(writer),
        Err(e) => {
            notify_event(env, state.target_ref.as_obj(), &format!("can't create audio file: {e}"));
            None
        }
    });
    let (tx, rx) = mpsc::channel();
    let jvm = state.jvm.clone();
    let target = state.target_ref.clone();
    *state.record_tx.lock().unwrap() = Some(tx);
    state.recorder = Some(std::thread::spawn(move || {
        crate::audio::record(rx, wav, 16_000, |event| {
            if let Ok(mut env) = jvm.attach_current_thread() {
                notify_event(&mut env, target.as_obj(), event);
            }
        })
    }));
}

/// Ends the streaming worker without a result.
fn abandon_stream(state: &mut VoiceSessionState) {
    if let Some(worker) = state.stream_worker.take() {
        worker.cancelled.store(true, Ordering::SeqCst);
    }
    state.stream_tx.lock().unwrap().take();
}

/// Stops capture and transcribes from the saved file, a run at a time. Never
/// blocks: this is called on the UI thread.
pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    // Drop the stream to stop recording; end the auto-stop monitor if running.
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;

    state.record_tx.lock().unwrap().take();
    let recorder = state.recorder.take();
    let wav_path = state.wav_path.take();
    let cancelled = Arc::new(AtomicBool::new(false));
    state.transcription_cancel = cancelled.clone();

    // Closing the channel tells the streaming worker the recording ended.
    state.stream_tx.lock().unwrap().take();
    let worker = state.stream_worker.take();

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();

    notify_status(&mut env, target_ref.as_obj(), "Transcribing...");

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        let unsaved = recorder.and_then(|r| r.join().ok()).unwrap_or_default();
        let mut recording = match crate::audio::Recording::open(wav_path.as_deref(), unsaved) {
            Ok(recording) => recording,
            Err(e) => {
                notify_status(&mut env, obj, &format!("Error: can't read the recording: {e}"));
                return;
            }
        };
        let total = recording.total_samples();
        // Mic permission denied, instant stop, etc.
        if total == 0 {
            notify_status(&mut env, obj, "Error: no audio recorded. Check microphone permissions.");
            return;
        }

        if let Some(worker) = worker {
            let result = worker.result.recv();
            if cancelled.load(Ordering::SeqCst) {
                notify_status(&mut env, obj, "Ready");
                notify_canceled(&mut env, obj);
                return;
            }
            match result {
                Ok(Ok(Some(text))) => {
                    notify_status(&mut env, obj, "Ready");
                    notify_text(&mut env, obj, &text);
                    return;
                }
                // Fall back to transcribing the recording offline.
                Ok(Err(e)) => log::warn!("streaming failed ({}); transcribing offline", e),
                _ => log::warn!("streaming ended without a result; transcribing offline"),
            }
        }

        // Wait for the engine if it is still loading, or reload it if the
        // model settings changed since it was loaded.
        if !engine::is_current() {
            if let Err(e) = engine::ensure_loaded(&mut env, obj) {
                notify_status(&mut env, obj, &format!("Error: {}", e));
                return;
            }
        }
        let Some(eng) = engine::get_engine() else {
            notify_status(&mut env, obj, "Error: model not loaded");
            return;
        };

        let result = engine::transcribe_recording(&eng, &mut recording, &cancelled, |text, done| {
            notify_progress(&mut env, obj, text, done, total);
        });
        match result {
            engine::RecordingResult::Done(text) => {
                notify_status(&mut env, obj, "Ready");
                notify_text(&mut env, obj, &text);
            }
            engine::RecordingResult::Canceled => {
                notify_status(&mut env, obj, "Ready");
                notify_canceled(&mut env, obj);
            }
            engine::RecordingResult::Failed(e) => {
                notify_status(&mut env, obj, &format!("Error: {}", e))
            }
        }
    });
}

/// Asks the transcription of the last stopped recording to stop after the
/// run in progress.
pub fn cancel_transcription(state: &VoiceSessionState) {
    state.transcription_cancel.store(true, Ordering::SeqCst);
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;
    abandon_stream(state);
    state.record_tx.lock().unwrap().take();
    state.recorder = None;
    state.wav_path = None;
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}
