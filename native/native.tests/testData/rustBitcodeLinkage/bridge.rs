#![no_std]

extern "C" {
    fn kotlin_increment(value: i32) -> i32;
}

#[no_mangle]
pub extern "C" fn rust_round_trip(value: i32) -> i32 {
    unsafe { kotlin_increment(value * 2 + 1) }
}
