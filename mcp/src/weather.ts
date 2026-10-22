import type { Config } from "./config.js";

/**
 * `get_weather` backed by Pirate Weather (keyed; adds official alerts and next-hour rain). The output has
 * the same shape as the phone's Open-Meteo `get_weather`, which stays as the fallback (plan §4b): no day
 * for now and tonight, one day for its summary and hours, a range for daily summaries.
 */

export class WeatherError extends Error {}

export interface WeatherRequest {
  place: string;
  day?: string;
  endDay?: string;
}

interface Place {
  name: string;
  latitude: number;
  longitude: number;
}

interface DataPoint {
  time: number;
  summary?: string;
  temperature?: number;
  apparentTemperature?: number;
  temperatureMax?: number;
  temperatureMin?: number;
  temperatureHigh?: number;
  temperatureLow?: number;
  precipProbability?: number;
  humidity?: number;
  windSpeed?: number;
}

interface Alert {
  title: string;
  severity?: string;
  expires?: number;
  description?: string;
}

interface Forecast {
  timezone: string;
  currently: DataPoint;
  minutely?: { summary?: string };
  hourly: { data: DataPoint[] };
  daily: { data: DataPoint[] };
  alerts?: Alert[];
}

const UNITS = {
  us: { temperature: "°F", speed: "mph" },
  si: { temperature: "°C", speed: "m/s" },
  ca: { temperature: "°C", speed: "km/h" },
  uk: { temperature: "°C", speed: "mph" },
} as const;

export async function weather(config: Config, request: WeatherRequest): Promise<Record<string, unknown>> {
  const day = request.day ? parseDate(request.day, "day") : undefined;
  const endDay = request.endDay && request.endDay !== request.day ? parseDate(request.endDay, "end_day") : undefined;
  if (endDay && !day) throw new WeatherError("end_day needs day as the start of the range");
  if (endDay && day && endDay <= day) throw new WeatherError("end_day must come after day");

  const place = await geocode(request.place);
  const forecast = await fetchForecast(config, place);
  const zone = forecast.timezone;
  const units = UNITS[config.weatherUnits];
  const temp = (value: number | undefined) => (value === undefined ? undefined : `${Math.round(value)}${units.temperature}`);
  const percent = (value: number | undefined) => (value === undefined ? undefined : `${Math.round(value * 100)}%`);
  const condition = (point: DataPoint) => point.summary?.toLowerCase();

  const today = local(forecast.currently.time, zone).date;
  const days = forecast.daily.data;
  const furthest = days.at(-1);
  if (day && day < today) throw new WeatherError("Only forecasts are available, not past weather");
  const lastAsked = endDay ?? day;
  if (lastAsked && furthest && lastAsked > local(furthest.time, zone).date) {
    throw new WeatherError(`The forecast only reaches ${local(furthest.time, zone).dayLabel}`);
  }

  const dayJson = (point: DataPoint) => ({
    date: local(point.time, zone).dayLabel,
    high: temp(point.temperatureMax ?? point.temperatureHigh),
    low: temp(point.temperatureMin ?? point.temperatureLow),
    condition: condition(point),
    precipitation_chance: percent(point.precipProbability),
  });
  // Every third hour keeps the result short and still covers morning, afternoon, evening, and night.
  const hoursJson = (points: DataPoint[], withDay: boolean) =>
    points
      .filter((point) => local(point.time, zone).hour % 3 === 0)
      .map((point) => ({
        time: withDay ? local(point.time, zone).hourWithDay : local(point.time, zone).hourLabel,
        temperature: temp(point.temperature),
        condition: condition(point),
        precipitation_chance: percent(point.precipProbability),
      }));

  const result: Record<string, unknown> = { place: place.name };
  if (!day) {
    const now = forecast.currently;
    result.now = {
      temperature: temp(now.temperature),
      feels_like: temp(now.apparentTemperature),
      condition: condition(now),
      wind: now.windSpeed === undefined ? undefined : `${Math.round(now.windSpeed)} ${units.speed}`,
      humidity: percent(now.humidity),
    };
    if (forecast.minutely?.summary) result.next_hour = forecast.minutely.summary;
    const todayPoint = days.find((point) => local(point.time, zone).date === today);
    if (todayPoint) result.today = dayJson(todayPoint);
    // The rest of today and tonight, until 6 AM tomorrow.
    const tomorrow = nextDate(today);
    result.hours = hoursJson(
      forecast.hourly.data.filter((point) => {
        const at = local(point.time, zone);
        return point.time > now.time && (at.date === today || (at.date === tomorrow && at.hour <= 6));
      }),
      true,
    );
  } else if (!endDay) {
    const dayPoint = days.find((point) => local(point.time, zone).date === day);
    if (dayPoint) result.day = dayJson(dayPoint);
    result.hours = hoursJson(forecast.hourly.data.filter((point) => local(point.time, zone).date === day), false);
  } else {
    result.days = days.filter((point) => {
      const date = local(point.time, zone).date;
      return date >= day! && date <= endDay;
    }).map(dayJson);
  }
  const alerts = (forecast.alerts ?? []).map((alert) => ({
    title: alert.title,
    severity: alert.severity,
    until: alert.expires ? local(alert.expires, zone).hourWithDay : undefined,
    description: alert.description?.replace(/\s+/g, " ").slice(0, 400),
  }));
  if (alerts.length > 0) result.alerts = alerts;
  return result;
}

/**
 * Open-Meteo's geocoding (free, no key) for the place name. "City, Region" picks the match whose region
 * or country contains the part after the comma.
 */
async function geocode(query: string): Promise<Place> {
  const [name = "", ...rest] = query.split(",").map((part) => part.trim());
  const qualifier = rest.join(" ").toLowerCase();
  const url = new URL("https://geocoding-api.open-meteo.com/v1/search");
  url.searchParams.set("name", name);
  url.searchParams.set("count", "10");
  url.searchParams.set("language", "en");
  const json = (await getJson(url, {}, "Place lookup")) as {
    results?: { name: string; latitude: number; longitude: number; admin1?: string; country?: string; country_code?: string }[];
  };
  const matches = json.results ?? [];
  const best =
    matches.find((m) => qualifier && [m.admin1, m.country, m.country_code].some((field) => field && qualifier.includes(field.toLowerCase()))) ??
    matches[0];
  if (!best) throw new WeatherError(`Couldn't find a place called ${query}`);
  return { name: [best.name, best.admin1].filter(Boolean).join(", "), latitude: best.latitude, longitude: best.longitude };
}

async function fetchForecast(config: Config, place: Place): Promise<Forecast> {
  // The key goes in a header, so it never appears in a URL (or a log of one); the path needs a placeholder.
  const url = new URL(`https://api.pirateweather.net/forecast/key/${place.latitude.toFixed(2)},${place.longitude.toFixed(2)}`);
  url.searchParams.set("units", config.weatherUnits);
  url.searchParams.set("extend", "hourly");
  return (await getJson(url, { apikey: config.pirateWeatherApiKey ?? "" }, "Weather service")) as Forecast;
}

async function getJson(url: URL, headers: Record<string, string>, what: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(url, { headers: { accept: "application/json", ...headers }, signal: AbortSignal.timeout(10_000) });
  } catch (e) {
    throw new WeatherError(`${what} unreachable: ${String(e)}`);
  }
  if (!response.ok) throw new WeatherError(`${what} returned ${response.status}`);
  return response.json();
}

function parseDate(value: string, key: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(Date.parse(value))) {
    throw new WeatherError(`${key} must be a date like 2026-10-24`);
  }
  return value;
}

function nextDate(date: string): string {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}

/** A UNIX time as the forecast place's local date and spoken-friendly labels. */
function local(unixSeconds: number, timeZone: string) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "numeric", hourCycle: "h23", weekday: "long",
    }).formatToParts(new Date(unixSeconds * 1000)).map((part) => [part.type, part.value]),
  );
  const date = `${parts.year}-${parts.month}-${parts.day}`;
  const hour = Number(parts.hour);
  const hourLabel = `${hour % 12 === 0 ? 12 : hour % 12} ${hour < 12 ? "AM" : "PM"}`;
  const dayLabel = new Intl.DateTimeFormat("en-US", { timeZone, weekday: "long", month: "long", day: "numeric" }).format(new Date(unixSeconds * 1000));
  return { date, hour, hourLabel, hourWithDay: `${parts.weekday} ${hourLabel}`, dayLabel };
}
