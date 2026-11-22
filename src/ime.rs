use jni::objects::{JClass, JObject, JString};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, VoiceSessionState};

static IME_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service);
    *IME_STATE.lock().unwrap() = Some(state);
}

/// Only clears the session if it belongs to `service` (see recognize.rs).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    // Android may create the next service before destroying the previous one,
    // which must not tear down the new one's session.
    let mut guard = IME_STATE.lock().unwrap();
    if guard
        .as_ref()
        .is_some_and(|s| env.is_same_object(&s.target_ref, &service).unwrap_or(false))
    {
        *guard = None;
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    mut env: JNIEnv,
    _class: JClass,
    wav_path: JString,
) -> jni::sys::jboolean {
    // Null when the history doesn't keep audio.
    let path: Option<String> = if wav_path.is_null() {
        None
    } else {
        env.get_string(&wav_path).ok().map(|s| s.into())
    };
    let mut guard = IME_STATE.lock().unwrap();
    let started = match guard.as_mut() {
        // The IME keyboard is manual tap-to-stop; no silence auto-stop.
        Some(state) => voice_session::start_recording(env, state, false, path),
        None => false,
    };
    started.into()
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelTranscription(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(state) = IME_STATE.lock().unwrap().as_ref() {
        voice_session::cancel_transcription(state);
    }
}
