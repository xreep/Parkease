import { QRCodeSVG } from 'qrcode.react'

/** The code the owner scans at the entrance. Always dark on white so it scans in dark mode too. */
export function BookingQr({ bookingCode }: { bookingCode: string }) {
  return (
    <section
      aria-label="Booking QR code"
      className="flex flex-col items-center gap-3 rounded-2xl border border-slate-200 bg-white p-5 text-center dark:border-slate-800 dark:bg-slate-900"
    >
      <div className="rounded-xl bg-white p-3 shadow-sm ring-1 ring-slate-200">
        <QRCodeSVG value={`PARKEASE:${bookingCode}`} size={180} level="M" bgColor="#ffffff" fgColor="#0f172a" title={`QR code for booking ${bookingCode}`} />
      </div>
      <p className="max-w-[16rem] text-sm text-slate-600 dark:text-slate-400">Show this code at the parking entrance.</p>
    </section>
  )
}
