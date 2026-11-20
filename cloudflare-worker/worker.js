/**
 * SFD Cloudflare Worker - CORS Proxy & Cookie Relay for sfd.ui.ac.ir
 * 
 * Free serverless proxy to bypass CORS and manage ASP.NET session cookies
 * seamlessly for the SFD PWA on GitHub Pages / iOS Safari.
 */

const TARGET_HOST = "https://sfd.ui.ac.ir";

export default {
  async fetch(request, env, ctx) {
    const origin = request.headers.get("Origin") || "*";

    // 1. Handle CORS Preflight (OPTIONS)
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: getCorsHeaders(origin)
      });
    }

    try {
      const url = new URL(request.url);

      // Root info page for users testing their worker deployment
      if (url.pathname === "/" && !url.searchParams.has("url") && request.method === "GET") {
        return new Response(
          JSON.stringify({
            status: "active",
            message: "SFD Proxy Worker is online!",
            target: TARGET_HOST,
            time: new Date().toISOString()
          }, null, 2),
          {
            status: 200,
            headers: {
              "Content-Type": "application/json; charset=utf-8",
              ...getCorsHeaders(origin)
            }
          }
        );
      }

      // 2. Resolve target URL
      let targetPath = url.pathname + url.search;
      if (url.searchParams.has("url")) {
        const rawUrl = url.searchParams.get("url");
        targetPath = rawUrl.startsWith("http") ? rawUrl.replace(TARGET_HOST, "") : rawUrl;
      }
      // If path starts with /api/, strip it
      if (targetPath.startsWith("/api/")) {
        targetPath = targetPath.replace("/api/", "/");
      }
      const targetUrl = `${TARGET_HOST}${targetPath}`;

      // 3. Prepare Forward Headers
      const forwardHeaders = new Headers();
      
      // Standard browser headers
      forwardHeaders.set("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1");
      forwardHeaders.set("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7");
      forwardHeaders.set("Accept", request.headers.get("Accept") || "*/*");

      // Custom or incoming Referer
      const customReferer = request.headers.get("X-SFD-Referer") || request.headers.get("Referer");
      if (customReferer && customReferer.includes("sfd.ui.ac.ir")) {
        forwardHeaders.set("Referer", customReferer);
      } else {
        forwardHeaders.set("Referer", `${TARGET_HOST}/`);
      }

      // Pass Content-Type for POST
      const contentType = request.headers.get("Content-Type");
      if (contentType) {
        forwardHeaders.set("Content-Type", contentType);
      }

      // 4. Cookie Relay: Client sends 'X-SFD-Cookie' to avoid Safari 3rd-party cookie drops
      const clientCookie = request.headers.get("X-SFD-Cookie") || request.headers.get("Cookie");
      if (clientCookie) {
        forwardHeaders.set("Cookie", clientCookie);
      }

      // 5. Build Fetch Request to sfd.ui.ac.ir
      const fetchOptions = {
        method: request.method,
        headers: forwardHeaders,
        redirect: "manual" // Handle redirects manually to capture cookies and forward location
      };

      if (request.method !== "GET" && request.method !== "HEAD") {
        fetchOptions.body = await request.arrayBuffer();
      }

      const upstreamResponse = await fetch(targetUrl, fetchOptions);

      // 6. Collect Upstream Cookies
      const collectedCookies = [];
      if (typeof upstreamResponse.headers.getSetCookie === "function") {
        collectedCookies.push(...upstreamResponse.headers.getSetCookie());
      } else {
        // Fallback
        const singleSetCookie = upstreamResponse.headers.get("set-cookie");
        if (singleSetCookie) {
          collectedCookies.push(singleSetCookie);
        }
      }

      // 7. Build Client Response with CORS & Exposed Headers
      const responseHeaders = new Headers(upstreamResponse.headers);

      // Inject CORS headers
      const cors = getCorsHeaders(origin);
      for (const [k, v] of Object.entries(cors)) {
        responseHeaders.set(k, v);
      }

      // Send cookies back via custom header 'X-SFD-Set-Cookie' (JSON or semicolon-delimited)
      if (collectedCookies.length > 0) {
        // Store name=value tokens
        const cookieTokens = collectedCookies.map(c => c.split(";")[0].trim()).join("; ");
        responseHeaders.set("X-SFD-Set-Cookie", cookieTokens);
        responseHeaders.set("X-SFD-Raw-Cookies", JSON.stringify(collectedCookies));
      }

      // Forward Location header for redirects (e.g., 302 redirects)
      const location = upstreamResponse.headers.get("Location");
      if (location) {
        responseHeaders.set("Location", location);
        responseHeaders.set("X-SFD-Location", location);
      }

      return new Response(upstreamResponse.body, {
        status: upstreamResponse.status,
        statusText: upstreamResponse.statusText,
        headers: responseHeaders
      });

    } catch (err) {
      return new Response(
        JSON.stringify({
          error: true,
          message: err.message || "Failed to communicate with SFD university server."
        }),
        {
          status: 502,
          headers: {
            "Content-Type": "application/json; charset=utf-8",
            ...getCorsHeaders(origin)
          }
        }
      );
    }
  }
};

function getCorsHeaders(origin) {
  return {
    "Access-Control-Allow-Origin": origin || "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS, HEAD",
    "Access-Control-Allow-Headers": "Content-Type, X-SFD-Cookie, X-SFD-Referer, X-Requested-With, Referer, Accept, Range",
    "Access-Control-Expose-Headers": "X-SFD-Set-Cookie, X-SFD-Raw-Cookies, X-SFD-Location, Location, Content-Type, Content-Length",
    "Access-Control-Allow-Credentials": "true",
    "Access-Control-Max-Age": "86400"
  };
}
