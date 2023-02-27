#![allow(non_snake_case)]

use std::{panic, process};

use android_logger::Config;

use anyhow::anyhow;
use jni::{
    objects::{GlobalRef, JValue},
    JNIEnv, JavaVM,
};
use jni::{
    objects::{JClass, JObject},
    sys::jint,
};
use log::{debug, error, LevelFilter};

use once_cell::sync::OnceCell;

use anyhow::Result;
use server::PpaassVpnServer;

mod device;
mod server;
mod tcp;
mod util;

pub(crate) const IP_MTU: usize = 1500;

pub(crate) static mut JAVA_VPN_SERVICE_OBJ: OnceCell<GlobalRef> = OnceCell::new();
pub(crate) static mut JAVA_VPN_JVM: OnceCell<JavaVM> = OnceCell::new();

/// # Safety
///
/// This function should not be called before the horsemen are ready.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ppaass_agent_rust_jni_RustLibrary_stopVpn(_jni_env: JNIEnv, _class: JClass) {
    JAVA_VPN_JVM.take();
    JAVA_VPN_SERVICE_OBJ.take();
}

/// # Safety
///
/// This function should not be called before the horsemen are ready.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ppaass_agent_rust_jni_RustLibrary_startVpn(
    jni_env: JNIEnv<'static>,
    _class: JClass<'static>,
    vpn_tun_device_fd: jint,
    vpn_service: JObject<'static>,
) {
    android_logger::init_once(Config::default().with_tag("PPAASS-VPN-RUST").with_max_level(LevelFilter::Debug));
    let java_vm = jni_env.get_java_vm().expect("Fail to get jvm from jni enviorment.");
    JAVA_VPN_JVM.set(java_vm).expect("Fail to save jvm to global reference.");
    JAVA_VPN_SERVICE_OBJ
        .set(jni_env.new_global_ref(vpn_service).expect("Fail to generate java vpn service object globale reference"))
        .expect("Fail to save java vpn service object to global reference");

    let ppaass_vpn_server = PpaassVpnServer::new(vpn_tun_device_fd).expect("Fail to generate ppaass vpn server.");
    if let Err(e) = ppaass_vpn_server.start() {
        error!("Fail to start ppaass vpn server because of error: {e:?}");
    };
}

pub(crate) fn protect_socket(socket_fd: i32) -> Result<()> {
    debug!("Begin to protect outbound socket: {socket_fd}");
    let socket_fd_jni_arg = JValue::Int(socket_fd);
    let java_vpn_service_obj = unsafe { JAVA_VPN_SERVICE_OBJ.get_mut() }.expect("Fail to get java vpn service object from global ref").as_obj();

    let java_vm = unsafe { JAVA_VPN_JVM.get_mut() }.expect("Fail to get jvm from global");

    let jni_env = java_vm.attach_current_thread_permanently().expect("Fail to attach jni env to current thread");
    let protect_result = jni_env.call_method(java_vpn_service_obj, "protect", "(I)Z", &[socket_fd_jni_arg]);
    let protect_result = match protect_result {
        Ok(protect_result) => protect_result,
        Err(e) => {
            error!("Fail to protect socket because of error: {e:?}");
            return Err(anyhow!("Fail to protect socket because of error: {e:?}"));
        },
    };
    match protect_result.z() {
        Ok(true) => {
            debug!("Call java vpn service object protect socket java method success, socket raw fd: {socket_fd}");
            Ok(())
        },
        Ok(false) => {
            error!("Fail to convert protect socket result because of return false");
            Err(anyhow!("Fail to protect socket because of result is false"))
        },
        Err(e) => {
            error!("Fail to convert protect socket result because of error: {e:?}");
            Err(anyhow!("Fail to convert protect socket result because of error: {e:?}"))
        },
    }
}
