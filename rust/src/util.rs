use smoltcp::wire::{pretty_print::PrettyPrint, PrettyPrinter};

pub(crate) fn print_packet<T>(packet: &T) -> String
where
    T: PrettyPrint + AsRef<[u8]>,
{
    format!("{}", PrettyPrinter::<T>::new("", packet))
}

pub(crate) fn print_packet_bytes<T>(packet_bytes: impl AsRef<[u8]>) -> String
where
    T: PrettyPrint + AsRef<[u8]>,
{
    format!("{}", PrettyPrinter::<T>::new("", &packet_bytes))
}
