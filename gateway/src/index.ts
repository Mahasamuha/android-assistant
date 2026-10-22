import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { TokenVerifier } from "./auth.js";
import { ResponseCapture } from "./capture.js";
import { loadConfig } from "./config.js";
import { HttpError } from "./errors.js";
import { BodyLog, DebugSwitch, log } from "./logging.js";
import { forwardInference } from "./proxy.js";
import { RateLimiter } from "./rateLimit.js";

const config = loadConfig();
const verifier = new TokenVerifier(config);
const limiter = new RateLimiter(config.rateLimit.perMinute, config.rateLimit.burst);
const debug = new DebugSwitch(config.debugDurationMs);
const bodyLog = new BodyLog(config.logDir, config.logBodyRetentionMs, debug, config.debugDurationMs);
await bodyLog.init();

// The only routes: GET /healthz and the inference routes below, each forwarded to the same path upstream.
// Everything else is 404. Routes are added deliberately and recorded in docs/plan.md.
const INFERENCE_ROUTES = new Set(["/v1/responses"]);

async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const requestId = randomUUID();
  const startedAt = performance.now();
  const { pathname } = new URL(req.url ?? "/", "http://gateway");

  if (req.method === "GET" && pathname === "/healthz") {
    sendJson(res, 200, { status: "ok" });
    return;
  }

  let status = 500;
  let subject: string | undefined;
  let request: unknown;
  let capture: ResponseCapture | undefined;
  try {
    if (req.method !== "POST" || !INFERENCE_ROUTES.has(pathname)) throw new HttpError(404, "Not found");

    subject = (await verifier.verify(req.headers.authorization)).subject;

    const retryAfter = limiter.take(subject);
    if (retryAfter > 0) throw new HttpError(429, "Rate limit exceeded", { "retry-after": String(retryAfter) });

    const body = await readBody(req, config.maxBodyBytes);
    request = parseJsonObject(body);

    if (bodyLog.enabled) capture = new ResponseCapture();
    status = await forwardInference(config, pathname, requestId, body, res, capture);
  } catch (e) {
    status = e instanceof HttpError ? e.status : 500;
    if (!(e instanceof HttpError)) log("unhandled_error", { requestId, error: e instanceof Error ? e.stack : String(e) });
    if (res.headersSent) res.destroy();
    else sendError(res, e);
  } finally {
    const latencyMs = Math.round(performance.now() - startedAt);
    log("request", { requestId, method: req.method, path: pathname, status, subject, latencyMs });
    if (capture) {
      bodyLog
        .write({ time: new Date().toISOString(), requestId, subject, status, latencyMs, debug: debug.on, request, response: capture.summarize(debug.on) })
        .catch((e: unknown) => log("body_log_failed", { requestId, error: String(e) }));
    }
  }
}

async function readBody(req: IncomingMessage, maxBytes: number): Promise<Buffer<ArrayBuffer>> {
  if (Number(req.headers["content-length"]) > maxBytes) throw new HttpError(413, "Request body too large", { connection: "close" });
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req as AsyncIterable<Buffer>) {
    size += chunk.length;
    if (size > maxBytes) throw new HttpError(413, "Request body too large", { connection: "close" });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function parseJsonObject(body: Buffer): unknown {
  let value: unknown;
  try {
    value = JSON.parse(body.toString("utf8"));
  } catch {
    throw new HttpError(400, "Request body must be JSON");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new HttpError(400, "Request body must be a JSON object");
  return value;
}

function sendError(res: ServerResponse, e: unknown): void {
  const error = e instanceof HttpError ? e : new HttpError(500, "Internal error");
  sendJson(res, error.status, { error: { message: error.message, code: error.status } }, error.headers);
}

function sendJson(res: ServerResponse, status: number, value: unknown, headers: Record<string, string> = {}): void {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store", ...headers });
  res.end(JSON.stringify(value));
}

const server = createServer((req, res) => void handle(req, res));
server.listen(config.port, () => log("listening", { port: config.port, bodyLogRetentionMs: config.logBodyRetentionMs }));

process.on("SIGUSR2", () => debug.toggle());
for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => {
    log("shutdown", { signal });
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 10_000).unref();
  });
}
