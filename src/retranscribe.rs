//! Native backend for `RetranscribeService`: transcribes a recording from the
//! history again.

use std::sync::atomic::{AtomicBool, Ordering};

use jni::objects::{JClass, JObject, JString};
use jni::sys::jint;
use jni::JNIEnv;

use crate::engine::{self, RecordingResult};

// Mirrored in RetranscribeService.java.
const DONE: jint = 0;
const CANCELED: jint = 1;
const FAILED: jint = 2;

/// The service runs one job at a time.
static CANCELLED: AtomicBool = AtomicBool::new(false);

/// Called before a job is queued, so that a cancel sent right after isn't lost.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RetranscribeService_prepareNative(
    _env: JNIEnv,
    _class: JClass,
) {
    CANCELLED.store(false, Ordering::SeqCst);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RetranscribeService_cancelNative(
    _env: JNIEnv,
    _class: JClass,
) {
    CANCELLED.store(true, Ordering::SeqCst);
}

/// Blocks until done; reports through `onTranscriptProgress` after each run.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RetranscribeService_transcribeNative(
    mut env: JNIEnv,
    service: JObject,
    path: JString,
) -> jint {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let Ok(path) = env.get_string(&path).map(String::from) else {
        return FAILED;
    };
    let mut recording = match crate::audio::Recording::open(Some(&path), Vec::new()) {
        Ok(recording) => recording,
        Err(e) => {
            log::error!("can't open recording: {}", e);
            return FAILED;
        }
    };
    if !engine::is_current() && engine::ensure_loaded(&mut env, &service).is_err() {
        return FAILED;
    }
    let Some(eng) = engine::get_engine() else {
        return FAILED;
    };
    let total = recording.total_samples();
    let result = engine::transcribe_recording(&eng, &mut recording, &CANCELLED, |text, done| {
        crate::voice_session::notify_progress(&mut env, &service, text, done, total);
    });
    match result {
        RecordingResult::Done(_) => DONE,
        RecordingResult::Canceled => CANCELED,
        RecordingResult::Failed(e) => {
            log::error!("retranscription failed: {}", e);
            FAILED
        }
    }
}
