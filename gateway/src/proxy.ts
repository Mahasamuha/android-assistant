import { once } from "node:events";
import type { ServerResponse } from "node:http";
import type { ResponseCapture } from "./capture.js";
import type { Config } from "./config.js";
import { HttpError } from "./errors.js";
import { log } from "./logging.js";

/** Status recorded when the client disconnects before the response completes. */
export const CLIENT_CLOSED = 499;

/**
 * Forwards a chat-completions request with the gateway's virtual key and streams the response back
 * byte for byte. Only content-type is forwarded from upstream: the LLM gateway's own headers stay here.
 */
export async function forwardChatCompletion(
  config: Config,
  requestId: string,
  body: Buffer<ArrayBuffer>,
  res: ServerResponse,
  capture: ResponseCapture | undefined,
): Promise<number> {
  // Client hang-ups cancel the upstream call so an abandoned answer stops costing tokens.
  const abort = new AbortController();
  const onClose = () => abort.abort();
  res.once("close", onClose);

  try {
    let upstream: Response;
    try {
      upstream = await fetch(`${config.upstreamUrl}/v1/chat/completions`, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${config.upstreamVirtualKey}` },
        body,
        signal: abort.signal,
      });
    } catch (e) {
      if (abort.signal.aborted) return CLIENT_CLOSED;
      log("upstream_unreachable", { requestId, error: String(e) });
      throw new HttpError(502, "LLM gateway unavailable");
    }

    // Upstream auth failures are a server misconfiguration. Passed through, the phone would read them as its own token being rejected.
    if (upstream.status === 401 || upstream.status === 403) {
      log("upstream_rejected_gateway", { requestId, status: upstream.status, body: (await upstream.text()).slice(0, 500) });
      throw new HttpError(502, "LLM gateway rejected the request");
    }

    const contentType = upstream.headers.get("content-type") ?? "application/json";
    res.writeHead(upstream.status, {
      "content-type": contentType,
      "cache-control": "no-store",
      ...(contentType.startsWith("text/event-stream") ? { "x-accel-buffering": "no" } : {}),
    });

    try {
      for await (const chunk of upstream.body ?? []) {
        capture?.add(chunk);
        if (!res.write(chunk)) await once(res, "drain", { signal: abort.signal });
      }
      res.end();
    } catch (e) {
      if (abort.signal.aborted) return CLIENT_CLOSED;
      throw e;
    }
    return upstream.status;
  } finally {
    res.off("close", onClose);
  }
}
