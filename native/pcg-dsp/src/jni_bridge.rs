//! JNI 绑定, 对应 Kotlin 类 com.phiragen.core.audio.NativeDsp.
//! 只依赖 jni-sys 的函数表, 所有入口都用 catch_unwind 包住, 避免 panic 越过 FFI 边界.

use crate::analysis::{Analysis, SpectrumStream};
use jni_sys::{jclass, jdoubleArray, jfloatArray, jint, jlong, JNIEnv};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

struct Session {
    stream: Option<SpectrumStream>,
    result: Option<Analysis>,
}

unsafe fn session<'a>(handle: jlong) -> Option<&'a mut Session> {
    if handle == 0 {
        None
    } else {
        Some(&mut *(handle as *mut Session))
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_phiragen_core_audio_NativeDsp_nativeCreate(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    catch_unwind(|| {
        Box::into_raw(Box::new(Session {
            stream: Some(SpectrumStream::new()),
            result: None,
        })) as jlong
    })
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_phiragen_core_audio_NativeDsp_nativePush(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    samples: jfloatArray,
    len: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let s = match session(handle) {
            Some(s) => s,
            None => return,
        };
        if len <= 0 {
            return;
        }
        let mut buf = vec![0f32; len as usize];
        ((**env).GetFloatArrayRegion.unwrap())(env, samples, 0, len, buf.as_mut_ptr());
        if let Some(stream) = s.stream.as_mut() {
            stream.push(&buf);
        }
    }));
}

/// 返回 0 成功, 1 音频太短, 其他为内部错误
#[no_mangle]
pub unsafe extern "system" fn Java_com_phiragen_core_audio_NativeDsp_nativeFinish(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| -> jint {
        let s = match session(handle) {
            Some(s) => s,
            None => return 2,
        };
        match s.stream.take() {
            Some(stream) => match stream.finish() {
                Ok(a) => {
                    s.result = Some(a);
                    0
                }
                Err(_) => 1,
            },
            None => 3,
        }
    }))
    .unwrap_or(4)
}

/// [时长, 速度, 相位, 帧数]
#[no_mangle]
pub unsafe extern "system" fn Java_com_phiragen_core_audio_NativeDsp_nativeMeta(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| -> jdoubleArray {
        let s = match session(handle) {
            Some(s) => s,
            None => return ptr::null_mut(),
        };
        let r = match s.result.as_ref() {
            Some(r) => r,
            None => return ptr::null_mut(),
        };
        let meta = [r.duration, r.bpm, r.phase, r.env.len() as f64];
        let arr = ((**env).NewDoubleArray.unwrap())(env, 4);
        if !arr.is_null() {
            ((**env).SetDoubleArrayRegion.unwrap())(env, arr, 0, 4, meta.as_ptr());
        }
        arr
    }))
    .unwrap_or(ptr::null_mut())
}

/// which: 0 env, 1 low, 2 mid, 3 high, 4 rms, 5 centroid, 6 low_mag
#[no_mangle]
pub unsafe extern "system" fn Java_com_phiragen_core_audio_NativeDsp_nativeArray(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    which: jint,
) -> jfloatArray {
    catch_unwind(AssertUnwindSafe(|| -> jfloatArray {
        let s = match session(handle) {
            Some(s) => s,
            None => return ptr::null_mut(),
        };
        let r = match s.result.as_ref() {
            Some(r) => r,
            None => return ptr::null_mut(),
        };
        let data: &[f32] = match which {
            0 => &r.env,
            1 => &r.low,
            2 => &r.mid,
            3 => &r.high,
            4 => &r.rms,
            5 => &r.centroid,
            6 => &r.low_mag,
            _ => return ptr::null_mut(),
        };
        let arr = ((**env).NewFloatArray.unwrap())(env, data.len() as jint);
        if !arr.is_null() {
            ((**env).SetFloatArrayRegion.unwrap())(env, arr, 0, data.len() as jint, data.as_ptr());
        }
        arr
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_phiragen_core_audio_NativeDsp_nativeDestroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        let _ = catch_unwind(AssertUnwindSafe(|| {
            drop(Box::from_raw(handle as *mut Session));
        }));
    }
}
