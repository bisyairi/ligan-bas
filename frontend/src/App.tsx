import { useEffect, useMemo, useState } from 'react'
import { Languages, Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Combobox,
  ComboboxCollection,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxLabel,
  ComboboxList,
} from '@/components/ui/combobox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { api, type Eta, type Route, type ScheduleResponse, type Stop, type StopHit } from '@/lib/api'
import { useLang } from '@/lib/i18n'
import { ensureSubscription, isIOS, isStandalone, pushSupported } from '@/lib/push'
import { useTheme } from '@/lib/theme'

const LS_KEY = 'busalert.subs'

interface Option {
  label: string
  value: string
}

interface RouteGroup {
  value: string
  items: Option[]
}

const PROVIDER_LABEL: Record<string, string> = {
  'rapid-bus-kl': 'Rapid KL',
  'rapid-bus-mrtfeeder': 'Rapid KL',
  'rapid-bus-penang': 'Rapid Penang',
  'rapid-rail-kl': 'Rapid Rail KL',
  'mybas-kangar': 'BAS.MY – Kangar',
  'mybas-alor-setar': 'BAS.MY – Alor Setar',
  'mybas-kota-bharu': 'BAS.MY – Kota Bharu',
  'mybas-kuala-terengganu': 'BAS.MY – Kuala Terengganu',
  'mybas-ipoh': 'BAS.MY – Ipoh',
  'mybas-melaka': 'BAS.MY – Melaka',
  'mybas-johor': 'BAS.MY – Johor',
  'mybas-kuching': 'BAS.MY – Kuching',
  'mybas-seremban-a': 'BAS.MY – Seremban A',
  'mybas-seremban-b': 'BAS.MY – Seremban B',
}

function providerLabel(routeKey: string): string {
  const cat = routeKey.split(':')[0]
  return PROVIDER_LABEL[cat] ?? cat
}

const isSameOption = (item: Option, value: Option) => item.value === value.value

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'))
const MINUTES = ['00', '05', '10', '15', '20', '25', '30', '35', '40', '45', '50', '55']

/** "HH:MM" via two selects, so the control looks the same on every platform (native time inputs don't). */
function TimeSelect({ id, value, onChange }: { id: string; value: string; onChange: (v: string) => void }) {
  const [h, m] = value.split(':')
  return (
    <div id={id} className="flex gap-1.5">
      <Select value={h} onValueChange={(nh) => onChange(`${nh}:${m}`)}>
        <SelectTrigger className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {HOURS.map((hh) => (
            <SelectItem key={hh} value={hh}>
              {hh}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select value={m} onValueChange={(nm) => onChange(`${h}:${nm}`)}>
        <SelectTrigger className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {MINUTES.map((mm) => (
            <SelectItem key={mm} value={mm}>
              {mm}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

interface MineSub {
  id: string
  token: string
  title: string
  provider: string
  when: string
}

type MsgCls = 'warn' | 'bad' | 'ok'
type Msg = { text: string; cls?: MsgCls } | null

function loadMine(): MineSub[] {
  try {
    return JSON.parse(localStorage.getItem(LS_KEY) || '[]')
  } catch {
    return []
  }
}

function saveMine(mine: MineSub[]) {
  localStorage.setItem(LS_KEY, JSON.stringify(mine))
}

/** "HH:MM" (hour can exceed 23 for a past-midnight trip) -> "h:mm AM/PM", with a "+1" mark past midnight. */
function formatTime12(time: string): string {
  const [hStr, m] = time.split(':')
  const h = Number(hStr)
  const wrapped = h % 24
  const period = wrapped < 12 ? 'AM' : 'PM'
  const h12 = wrapped % 12 === 0 ? 12 : wrapped % 12
  return `${h12}:${m} ${period}${h >= 24 ? ' (+1)' : ''}`
}

function msgClass(cls?: MsgCls) {
  if (cls === 'warn') return 'text-amber-600 dark:text-amber-400'
  if (cls === 'bad') return 'text-red-600 dark:text-red-400'
  if (cls === 'ok') return 'text-teal-600 dark:text-teal-400'
  return 'text-muted-foreground'
}

export default function App() {
  const { theme, toggleTheme } = useTheme()
  const { lang, setLang, days: DAYS, t, stopsAway, everyMin, viaProvider } = useLang()

  const [routes, setRoutes] = useState<Route[]>([])
  const [routesError, setRoutesError] = useState<string | null>(null)
  const [routeOption, setRouteOption] = useState<Option | null>(null)
  const routeKey = routeOption?.value ?? ''

  const [stops, setStops] = useState<Stop[]>([])
  const [stopOption, setStopOption] = useState<Option | null>(null)
  const stopId = stopOption?.value ?? ''

  const [etaMsg, setEtaMsg] = useState<Msg>(null)
  const [etas, setEtas] = useState<Eta[] | null>(null)
  const [etaNote, setEtaNote] = useState('')

  const [scheduleOpen, setScheduleOpen] = useState(false)
  const [schedule, setSchedule] = useState<ScheduleResponse | null>(null)
  const [scheduleErr, setScheduleErr] = useState<string | null>(null)

  const [selectedDays, setSelectedDays] = useState<number[]>([0, 1, 2, 3, 4])
  const [start, setStart] = useState('07:00')
  const [end, setEnd] = useState('09:00')
  const [interval, setInterval_] = useState('5')

  const [stopQuery, setStopQuery] = useState('')
  const [stopHits, setStopHits] = useState<StopHit[]>([])
  const [stopHitChoice, setStopHitChoice] = useState<StopHit | null>(null)
  const [pendingStopId, setPendingStopId] = useState<string | null>(null)

  const [subBusy, setSubBusy] = useState(false)
  const [subMsg, setSubMsg] = useState<Msg>(null)
  const [mine, setMine] = useState<MineSub[]>(() => loadMine())

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const r = await api<Route[]>('/api/routes')
        if (!cancelled) {
          setRoutes(r)
          setRoutesError(null)
        }
      } catch (e) {
        if (!cancelled) {
          setRoutesError((e as Error).message)
          setTimeout(load, 5000)
        }
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    setStopOption(null)
    setStops([])
    setEtaMsg(null)
    setEtas(null)
    if (!routeKey) return
    let cancelled = false
    api<Stop[]>('/api/stops?route=' + encodeURIComponent(routeKey))
      .then((s) => {
        if (cancelled) return
        setStops(s)
        if (pendingStopId) {
          const match = s.find((x) => x.id === pendingStopId)
          if (match) setStopOption({ label: match.name, value: match.id })
          setPendingStopId(null)
        }
      })
      .catch((e) => !cancelled && setEtaMsg({ text: (e as Error).message, cls: 'bad' }))
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeKey])

  useEffect(() => {
    if (stopQuery.trim().length < 2) {
      setStopHits([])
      return
    }
    let cancelled = false
    const timer = window.setTimeout(() => {
      api<StopHit[]>('/api/stops/search?q=' + encodeURIComponent(stopQuery))
        .then((hits) => !cancelled && setStopHits(hits))
        .catch(() => !cancelled && setStopHits([]))
    }, 300)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [stopQuery])

  function pickStopHit(hit: StopHit, route?: Route) {
    const chosen = route ?? hit.routes[0]
    if (!chosen) return
    if (!route && hit.routes.length > 1) {
      setStopHitChoice(hit)
      return
    }
    setStopHitChoice(null)
    setPendingStopId(hit.id)
    const match = routes.find((r) => r.key === chosen.key)
    setRouteOption(match ? { label: match.label, value: match.key } : { label: chosen.label, value: chosen.key })
    setStopQuery('')
    setStopHits([])
  }

  const routeGroups = useMemo<RouteGroup[]>(() => {
    const byProvider = new Map<string, Option[]>()
    for (const r of routes) {
      const label = providerLabel(r.key)
      const list = byProvider.get(label) ?? []
      list.push({ label: r.label, value: r.key })
      byProvider.set(label, list)
    }
    return Array.from(byProvider, ([value, items]) => ({ value, items }))
  }, [routes])
  const stopOptions = useMemo<Option[]>(() => stops.map((s) => ({ label: s.name, value: s.id })), [stops])

  const canAct = !!routeKey && !!stopId
  const selectedProvider = routeKey ? providerLabel(routeKey) : null

  const nextActiveTime = useMemo(() => {
    if (!schedule) return null
    const todayIndex = (new Date().getDay() + 6) % 7  // JS: 0=Sun..6=Sat -> 0=Mon..6=Sun
    if (schedule.day !== todayIndex) return null
    const now = new Date()
    const nowHHMM = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
    return schedule.times.find((time) => time >= nowHHMM) ?? null
  }, [schedule])

  async function checkEta() {
    setEtaMsg({ text: t('calculating') })
    setEtas(null)
    try {
      const q = new URLSearchParams({ route: routeKey, stop: stopId })
      const { etas: list, note } = await api<{ etas: Eta[]; note: string }>('/api/eta?' + q)
      if (!list.length) {
        setEtaMsg({ text: t('noBusNow'), cls: 'warn' })
        return
      }
      setEtaMsg(null)
      setEtas(list)
      setEtaNote(note)
    } catch (e) {
      setEtaMsg({ text: (e as Error).message, cls: 'bad' })
    }
  }

  function loadSchedule(day?: number) {
    setSchedule(null)
    setScheduleErr(null)
    const params: Record<string, string> = { route: routeKey, stop: stopId }
    if (day !== undefined) params.day = String(day)
    api<ScheduleResponse>('/api/schedule?' + new URLSearchParams(params))
      .then(setSchedule)
      .catch((e) => setScheduleErr((e as Error).message))
  }

  function openSchedule() {
    setScheduleOpen(true)
    loadSchedule()
  }

  function toggleDay(d: number) {
    setSelectedDays((prev) => (prev.includes(d) ? prev.filter((x) => x !== d) : [...prev, d].sort()))
  }

  async function subscribe() {
    if (!selectedDays.length) {
      setSubMsg({ text: t('pickOneDay'), cls: 'warn' })
      return
    }
    if (end <= start) {
      setSubMsg({ text: t('endBeforeStart'), cls: 'warn' })
      return
    }
    setSubBusy(true)
    setSubMsg({ text: t('preparing') })
    try {
      const { vapidPublicKey } = await api<{ vapidPublicKey: string }>('/api/config')
      const subscription = await ensureSubscription(vapidPublicKey)
      const body = {
        subscription,
        route: routeKey,
        stop_id: stopId,
        days: selectedDays,
        start,
        end,
        interval_min: Number(interval),
      }
      const { id, token } = await api<{ id: string; token: string }>('/api/subscriptions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const entry: MineSub = {
        id,
        token,
        title: `${routeOption?.label.split(' – ')[0] ?? routeKey} · ${stopOption?.label ?? stopId}`,
        provider: selectedProvider ?? '',
        when: `${selectedDays.map((d) => DAYS[d]).join(',')} ${start}–${end}, ${t('every')} ${interval} ${t('min')}`,
      }
      const next = [...mine, entry]
      setMine(next)
      saveMine(next)
      setSubMsg({ text: t('subDone'), cls: 'ok' })
    } catch (e) {
      setSubMsg({ text: (e as Error).message, cls: 'bad' })
    } finally {
      setSubBusy(false)
    }
  }

  async function removeMine(m: MineSub) {
    try {
      await api(`/api/subscriptions/${encodeURIComponent(m.id)}?token=${encodeURIComponent(m.token)}`, {
        method: 'DELETE',
      })
    } catch {
      // already gone on the server: remove locally anyway
    }
    const next = mine.filter((x) => x.id !== m.id)
    setMine(next)
    saveMine(next)
  }

  return (
    <main className="mx-auto max-w-[560px] px-4 pb-10 pt-[env(safe-area-inset-top)]">
      <div className="flex items-start justify-between gap-2 pt-6">
        <div>
          <h1 className="mb-1 text-2xl font-semibold">{t('title')}</h1>
          <p className="text-muted-foreground">{t('subtitle')}</p>
        </div>
        <div className="flex shrink-0 gap-1 pt-1">
          <Button
            variant="outline"
            size="icon"
            aria-label="Toggle language"
            onClick={() => setLang(lang === 'ms' ? 'en' : 'ms')}
          >
            <Languages />
          </Button>
          <Button variant="outline" size="icon" aria-label="Toggle theme" onClick={toggleTheme}>
            {theme === 'dark' ? <Sun /> : <Moon />}
          </Button>
        </div>
      </div>

      {isIOS && !isStandalone && (
        <Card className="mb-4 mt-4 border-amber-500/50 bg-amber-500/10">
          <CardContent className="text-sm">
            <b>iPhone:</b> {t('iosHint')}
          </CardContent>
        </Card>
      )}

      <Card className="mb-4 mt-4">
        <CardHeader>
          <CardTitle>{t('step1Title')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div>
            <Label className="mb-1">{t('stopSearchLabel')}</Label>
            <Combobox
              items={stopHits}
              value={null}
              onValueChange={(hit) => hit && pickStopHit(hit)}
              inputValue={stopQuery}
              onInputValueChange={setStopQuery}
              itemToStringLabel={(hit: StopHit) => hit.name}
            >
              <ComboboxInput placeholder={t('stopSearchPlaceholder')} />
              <ComboboxContent>
                <ComboboxEmpty>{t('noStopHits')}</ComboboxEmpty>
                <ComboboxList>
                  {(hit: StopHit) => (
                    <ComboboxItem key={hit.id} value={hit}>
                      {hit.name}
                      <span className="ml-auto text-xs text-muted-foreground">
                        {hit.routes.length > 1 ? `${hit.routes.length} bas` : hit.routes[0]?.label}
                      </span>
                    </ComboboxItem>
                  )}
                </ComboboxList>
              </ComboboxContent>
            </Combobox>
            {stopHitChoice && (
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                <span className="w-full text-xs text-muted-foreground">{t('whichBus')}</span>
                {stopHitChoice.routes.map((r) => (
                  <Button key={r.key} size="sm" variant="outline" onClick={() => pickStopHit(stopHitChoice, r)}>
                    {providerLabel(r.key)} · {r.label}
                  </Button>
                ))}
              </div>
            )}
          </div>

          <div>
            <Label className="mb-1">{t('routeLabel')}</Label>
            <Combobox
              items={routeGroups}
              value={routeOption}
              onValueChange={setRouteOption}
              isItemEqualToValue={isSameOption}
              disabled={!routes.length && !routesError}
            >
              <ComboboxInput placeholder={routesError ?? (routes.length ? t('routePlaceholder') : t('loading'))} />
              <ComboboxContent>
                <ComboboxEmpty>{t('noRoutes')}</ComboboxEmpty>
                <ComboboxList>
                  {(group: RouteGroup) => (
                    <ComboboxGroup key={group.value} items={group.items}>
                      <ComboboxLabel>{group.value}</ComboboxLabel>
                      <ComboboxCollection>
                        {(opt: Option) => (
                          <ComboboxItem key={opt.value} value={opt}>
                            {opt.label}
                          </ComboboxItem>
                        )}
                      </ComboboxCollection>
                    </ComboboxGroup>
                  )}
                </ComboboxList>
              </ComboboxContent>
            </Combobox>
            {selectedProvider && <p className="mt-1 text-xs text-muted-foreground">{viaProvider(selectedProvider)}</p>}
          </div>

          <div>
            <Label className="mb-1">{t('stopLabel')}</Label>
            <Combobox
              items={stopOptions}
              value={stopOption}
              onValueChange={setStopOption}
              isItemEqualToValue={isSameOption}
              disabled={!stops.length}
            >
              <ComboboxInput placeholder={routeKey ? t('stopPlaceholder') : t('pickRouteFirst')} />
              <ComboboxContent>
                <ComboboxEmpty>{t('noStops')}</ComboboxEmpty>
                <ComboboxList>
                  {(opt: Option) => (
                    <ComboboxItem key={opt.value} value={opt}>
                      {opt.label}
                    </ComboboxItem>
                  )}
                </ComboboxList>
              </ComboboxContent>
            </Combobox>
          </div>

          <div className="flex gap-2">
            <Button variant="outline" className="flex-1" disabled={!canAct} onClick={checkEta}>
              {t('checkEta')}
            </Button>
            <Dialog open={scheduleOpen} onOpenChange={setScheduleOpen}>
              <DialogTrigger asChild>
                <Button variant="outline" className="flex-1" disabled={!canAct} onClick={openSchedule}>
                  {t('viewSchedule')}
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>{t('scheduleTitle')}</DialogTitle>
                  <DialogDescription>
                    {routeOption?.label} · {stopOption?.label}
                  </DialogDescription>
                </DialogHeader>
                <div className="flex flex-wrap gap-1.5">
                  {DAYS.map((d, i) => (
                    <Button
                      key={d}
                      size="sm"
                      variant={schedule?.day === i ? 'default' : 'outline'}
                      onClick={() => loadSchedule(i)}
                    >
                      {d}
                    </Button>
                  ))}
                </div>
                {scheduleErr && <p className={`text-sm ${msgClass('bad')}`}>{scheduleErr}</p>}
                {!scheduleErr && !schedule && <p className="text-sm text-muted-foreground">{t('scheduleLoading')}</p>}
                {schedule && schedule.times.length === 0 && (
                  <p className="text-sm text-muted-foreground">{t('scheduleEmpty')}</p>
                )}
                {schedule && schedule.times.length > 0 && (
                  <div className="grid max-h-80 grid-cols-3 gap-1.5 overflow-y-auto">
                    {schedule.times.map((time) => (
                      <span
                        key={time}
                        className={
                          time === nextActiveTime
                            ? 'rounded-md border border-teal-600 bg-teal-600/15 px-2 py-1 text-center text-sm font-semibold whitespace-nowrap text-teal-700 dark:border-teal-400 dark:text-teal-400'
                            : 'rounded-md border px-2 py-1 text-center text-sm whitespace-nowrap'
                        }
                      >
                        {formatTime12(time)}
                      </span>
                    ))}
                  </div>
                )}
                <p className="text-xs text-muted-foreground">{t('scheduleNote')}</p>
              </DialogContent>
            </Dialog>
          </div>

          {etaMsg && <p className={`text-sm ${msgClass(etaMsg.cls)}`}>{etaMsg.text}</p>}
          {etas && (
            <div>
              {etas.map((e, i) => (
                <div key={i} className="border-t py-2 first:border-t-0">
                  <b className="text-lg">~{e.minutes} min</b> ·{' '}
                  {e.stops_away <= 0 ? t('almostThere') : stopsAway(e.stops_away)}{' '}
                  <small className="text-muted-foreground">
                    ({e.km} km, bas {e.vehicle})
                  </small>
                </div>
              ))}
              <small className="text-muted-foreground">{etaNote}</small>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="mb-4">
        <CardHeader>
          <CardTitle>{t('step2Title')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div>
            <Label className="mb-1">{t('daysLabel')}</Label>
            <div className="flex flex-wrap gap-1.5">
              {DAYS.map((d, i) => (
                <label key={d} className="flex items-center gap-1.5 rounded-full border px-2.5 py-1.5 text-sm">
                  <Checkbox checked={selectedDays.includes(i)} onCheckedChange={() => toggleDay(i)} />
                  {d}
                </label>
              ))}
            </div>
          </div>

          <div className="flex gap-2.5">
            <div className="flex-1">
              <Label htmlFor="start" className="mb-1">
                {t('fromLabel')}
              </Label>
              <TimeSelect id="start" value={start} onChange={setStart} />
            </div>
            <div className="flex-1">
              <Label htmlFor="end" className="mb-1">
                {t('toLabel')}
              </Label>
              <TimeSelect id="end" value={end} onChange={setEnd} />
            </div>
          </div>

          <div>
            <Label className="mb-1">{t('everyLabel')}</Label>
            <Select value={interval} onValueChange={setInterval_}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="1">{everyMin(1)}</SelectItem>
                <SelectItem value="2">{everyMin(2)}</SelectItem>
                <SelectItem value="5">{everyMin(5)}</SelectItem>
                <SelectItem value="10">{everyMin(10)}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <Button className="w-full" disabled={!canAct || !pushSupported || subBusy} onClick={subscribe}>
            {t('enableNotif')}
          </Button>

          {subMsg && <p className={`text-sm ${msgClass(subMsg.cls)}`}>{subMsg.text}</p>}
          {!pushSupported && <p className="text-sm text-amber-600 dark:text-amber-400">{t('pushUnsupported')}</p>}
        </CardContent>
      </Card>

      {mine.length > 0 && (
        <Card className="mb-4">
          <CardHeader>
            <CardTitle>{t('mySubsTitle')}</CardTitle>
          </CardHeader>
          <CardContent>
            {mine.map((m) => (
              <div key={m.id} className="flex items-center justify-between gap-2.5 border-t py-2 first:border-t-0">
                <div>
                  {m.title}
                  <small className="block text-muted-foreground">
                    {m.provider ? `${m.provider} · ` : ''}
                    {m.when}
                  </small>
                </div>
                <Button variant="outline" size="sm" onClick={() => removeMine(m)}>
                  {t('delete')}
                </Button>
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <Dialog>
        <DialogTrigger asChild>
          <button className="mt-4 text-sm text-muted-foreground underline underline-offset-2">{t('faqLink')}</button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('faqTitle')}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4 text-sm">
            <div>
              <p className="font-medium">{t('faqIosQ')}</p>
              <p className="text-muted-foreground">{t('faqIosA')}</p>
            </div>
            <div>
              <p className="font-medium">{t('faqTimeQ')}</p>
              <p className="text-muted-foreground">{t('faqTimeA')}</p>
            </div>
            <div>
              <p className="font-medium">{t('faqMultiQ')}</p>
              <p className="text-muted-foreground">{t('faqMultiA')}</p>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <footer className="mt-2 text-xs text-muted-foreground">{t('footer')}</footer>
    </main>
  )
}
