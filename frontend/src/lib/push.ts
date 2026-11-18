export const isIOS =
  /iphone|ipad|ipod/i.test(navigator.userAgent) ||
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)

export const isStandalone =
  window.matchMedia('(display-mode: standalone)').matches ||
  (navigator as any).standalone === true

export const pushSupported =
  'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window

function b64ToBytes(b64: string): Uint8Array {
  const pad = '='.repeat((4 - (b64.length % 4)) % 4)
  const raw = atob((b64 + pad).replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(raw, (c) => c.charCodeAt(0))
}

export async function ensureSubscription(vapidPublicKey: string) {
  if (isIOS && !isStandalone) {
    throw new Error('Di iPhone, kena buka dari ikon Home Screen dulu (Share → Add to Home Screen).')
  }
  const reg = await navigator.serviceWorker.register('/sw.js')
  await navigator.serviceWorker.ready
  const perm = await Notification.requestPermission()
  if (perm !== 'granted') {
    throw new Error('Kebenaran notifikasi ditolak. Boleh tukar dalam tetapan browser/telefon.')
  }
  let sub = await reg.pushManager.getSubscription()
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: b64ToBytes(vapidPublicKey) as BufferSource,
    })
  }
  return sub.toJSON()
}
