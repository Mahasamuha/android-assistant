export class ConfigError extends Error {}

export interface Config {
  port: number;
  braveApiKey: string;
  /** Country code Brave ranks results for. */
  searchCountry: string;
  /** Budget for one search result's text; about 4 characters per token. */
  searchMaxChars: number;
  /** Pages per search result; each gets an equal share of the text budget. */
  searchMaxPages: number;
  /** Pirate Weather key; without it the server offers no get_weather and the phone uses its own. */
  pirateWeatherApiKey: string | undefined;
  weatherUnits: "us" | "si" | "ca" | "uk";
  maxBodyBytes: number;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return {
    port: integer(env, "PORT", 8080),
    braveApiKey: required(env, "BRAVE_API_KEY"),
    searchCountry: env.SEARCH_COUNTRY || "us",
    searchMaxChars: integer(env, "SEARCH_MAX_CHARS", 24_000),
    searchMaxPages: integer(env, "SEARCH_MAX_PAGES", 8),
    pirateWeatherApiKey: env.PIRATE_WEATHER_API_KEY || undefined,
    weatherUnits: units(env, "WEATHER_UNITS", "us"),
    maxBodyBytes: integer(env, "MAX_BODY_BYTES", 64 * 1024),
  };
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) throw new ConfigError(`${name} is required`);
  return value;
}

function units(env: NodeJS.ProcessEnv, name: string, fallback: Config["weatherUnits"]): Config["weatherUnits"] {
  const value = env[name] || fallback;
  if (value !== "us" && value !== "si" && value !== "ca" && value !== "uk") throw new ConfigError(`${name} must be us, si, ca or uk`);
  return value;
}

function integer(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const raw = env[name];
  if (raw === undefined || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 0) throw new ConfigError(`${name} must be a non-negative integer`);
  return value;
}
