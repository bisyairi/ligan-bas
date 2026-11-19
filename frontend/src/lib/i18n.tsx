import { createContext, useContext, useState, type ReactNode } from 'react'

export type Lang = 'ms' | 'en'
const KEY = 'busalert.lang'

const DAYS: Record<Lang, string[]> = {
  ms: ['Isn', 'Sel', 'Rab', 'Kha', 'Jum', 'Sab', 'Ahd'],
  en: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
}

const STRINGS = {
  title: { ms: '🚌 Bus Alert', en: '🚌 Bus Alert' },
  subtitle: {
    ms: 'Pilih bas & stop, dapat notifikasi anggaran bas sampai pada waktu yang kau tetapkan.',
    en: 'Pick a bus & stop, get a notification with the estimated arrival at the time you set.',
  },
  iosHint: {
    ms: 'tekan butang Share → Add to Home Screen, buka dari ikon tu, baru boleh aktifkan notifikasi (iOS 16.4+).',
    en: 'tap Share → Add to Home Screen, open it from that icon, then you can enable notifications (iOS 16.4+).',
  },
  step1Title: { ms: '1. Pilih bas & stop', en: '1. Pick a bus & stop' },
  stopSearchLabel: { ms: 'Tak tahu bas apa? Cari ikut nama stop', en: "Don't know the bus? Search by stop name" },
  stopSearchPlaceholder: { ms: 'Taip nama stop…', en: 'Type the stop name…' },
  noStopHits: { ms: 'Tiada stop jumpa.', en: 'No stops found.' },
  whichBus: { ms: 'Stop ni ada beberapa bas, pilih satu:', en: 'This stop has a few buses, pick one:' },
  routeLabel: { ms: 'Route', en: 'Route' },
  routePlaceholder: { ms: 'Cari route (cth T811)…', en: 'Search route (e.g. T811)…' },
  loading: { ms: 'Memuatkan…', en: 'Loading…' },
  noRoutes: { ms: 'Tiada route jumpa.', en: 'No routes found.' },
  stopLabel: { ms: 'Stop', en: 'Stop' },
  stopPlaceholder: { ms: 'Cari nama stop…', en: 'Search stop name…' },
  pickRouteFirst: { ms: 'Pilih route dulu', en: 'Pick a route first' },
  noStops: { ms: 'Tiada stop jumpa.', en: 'No stops found.' },
  checkEta: { ms: 'Semak ETA sekarang', en: 'Check ETA now' },
  calculating: { ms: 'Mengira…', en: 'Calculating…' },
  noBusNow: {
    ms: 'Tiada bas yang menuju stop ni sekarang (mungkin di luar waktu operasi).',
    en: 'No bus heading to this stop right now (maybe outside operating hours).',
  },
  almostThere: { ms: 'hampir sampai', en: 'almost there' },
  viewSchedule: { ms: 'Lihat jadual', en: 'View schedule' },
  scheduleTitle: { ms: 'Jadual bas', en: 'Bus schedule' },
  scheduleLoading: { ms: 'Memuatkan jadual…', en: 'Loading schedule…' },
  scheduleEmpty: {
    ms: 'Tiada jadual statik untuk stop ni.',
    en: 'No static schedule for this stop.',
  },
  scheduleNote: {
    ms: 'Jadual statik GTFS untuk hari ini, bukan masa sebenar.',
    en: "Static GTFS schedule for today, not real-time.",
  },
  step2Title: { ms: '2. Jadual notifikasi', en: '2. Notification schedule' },
  daysLabel: { ms: 'Hari', en: 'Days' },
  fromLabel: { ms: 'Dari', en: 'From' },
  toLabel: { ms: 'Hingga', en: 'To' },
  everyLabel: { ms: 'Setiap', en: 'Every' },
  enableNotif: { ms: 'Aktifkan notifikasi', en: 'Enable notifications' },
  pickOneDay: { ms: 'Pilih sekurang-kurangnya satu hari.', en: 'Pick at least one day.' },
  endBeforeStart: {
    ms: "Masa 'Hingga' mesti lepas masa 'Dari' (maksimum 4 jam, dalam hari yang sama).",
    en: "'To' time must be after 'From' time (max 4 hours, same day).",
  },
  preparing: { ms: 'Menyediakan…', en: 'Setting up…' },
  subDone: {
    ms: 'Siap! Kau patut dapat satu notifikasi ujian sekarang.',
    en: 'Done! You should get a test notification now.',
  },
  pushUnsupported: {
    ms: 'Browser ni tak support Web Push. Guna Chrome (Android) atau Safari 16.4+ (iPhone, via Home Screen).',
    en: 'This browser doesn’t support Web Push. Use Chrome (Android) or Safari 16.4+ (iPhone, via Home Screen).',
  },
  mySubsTitle: { ms: 'Subscription aku (peranti ni)', en: 'My subscriptions (this device)' },
  every: { ms: 'setiap', en: 'every' },
  min: { ms: 'min', en: 'min' },
  delete: { ms: 'Padam', en: 'Delete' },
  footer: {
    ms: 'ETA dikira dari posisi GPS bas (data.gov.my GTFS Realtime) dan kelajuan purata, jadi ia anggaran, bukan ETA rasmi. Data live dikemas kini setiap ~30 saat. Tiada akaun atau data peribadi disimpan.',
    en: 'ETA is estimated from bus GPS position (data.gov.my GTFS Realtime) and average speed, so it’s an estimate, not an official ETA. Live data refreshes every ~30s. No account or personal data is stored.',
  },
} satisfies Record<string, Record<Lang, string>>

type Key = keyof typeof STRINGS

function stopsAway(lang: Lang, n: number) {
  return lang === 'ms' ? `${n} stop lagi` : `${n} stops away`
}
function everyMin(lang: Lang, n: number) {
  return lang === 'ms' ? `${n} minit` : `${n} min`
}
function viaProvider(lang: Lang, provider: string) {
  return lang === 'ms' ? `via ${provider}` : `via ${provider}`
}

const LangContext = createContext<{ lang: Lang; setLang: (l: Lang) => void } | null>(null)

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(() => (localStorage.getItem(KEY) === 'en' ? 'en' : 'ms'))
  const setLang = (l: Lang) => {
    localStorage.setItem(KEY, l)
    setLangState(l)
  }
  return <LangContext.Provider value={{ lang, setLang }}>{children}</LangContext.Provider>
}

export function useLang() {
  const ctx = useContext(LangContext)
  if (!ctx) throw new Error('useLang must be used within LanguageProvider')
  const { lang, setLang } = ctx
  return {
    lang,
    setLang,
    days: DAYS[lang],
    t: (key: Key) => STRINGS[key][lang],
    stopsAway: (n: number) => stopsAway(lang, n),
    everyMin: (n: number) => everyMin(lang, n),
    viaProvider: (p: string) => viaProvider(lang, p),
  }
}
