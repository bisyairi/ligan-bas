export interface Route {
  key: string
  label: string
}

export interface Stop {
  id: string
  name: string
}

export interface Eta {
  minutes: number
  stops_away: number
  km: number
  vehicle: string
}

export interface EtaResponse {
  etas: Eta[]
  note: string
}

export interface ScheduleResponse {
  day: number
  times: string[]
  note: string
}

export interface StopHit {
  id: string
  name: string
  routes: Route[]
}

export async function api<T>(path: string, opts?: RequestInit): Promise<T> {
  const r = await fetch(path, opts)
  let data: any = null
  try {
    data = await r.json()
  } catch {
    // no body
  }
  if (!r.ok) throw new Error((data && data.detail) || `Ralat ${r.status}`)
  return data as T
}
