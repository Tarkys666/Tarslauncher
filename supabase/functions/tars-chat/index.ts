import "jsr:@supabase/functions-js/edge-runtime.d.ts";

/**
 * TARS Launcher - AI Chat Proxy Edge Function
 * Routes client prompts to OpenRouter (`dots-studio/dots-3-note-preview:free`) or Groq (`qwen/qwen3.8-27b`).
 * Client only passes { model, messages, qwenEnabled, temperature, top_p }.
 * Uses direct server-side API keys or backend environment secrets.
 */

// DIRECT MODEL API KEYS (Server-Side)
// Telefon üzerinden API anahtarı girme zorunluluğunu ortadan kaldırmak için doğrudan Edge Function içinde kullanılır.
const DIRECT_OPENROUTER_API_KEY = ""; // OpenRouter API Key (sk-or-...)
const DIRECT_GROQ_API_KEY = "";       // Groq Cloud API Key (gsk_...)

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const body = await req.json();
    const {
      model = "dots-studio/dots-3-note-preview:free",
      messages = [],
      apiKey = "",
      qwenEnabled = false,
      temperature = 0.15,
      top_p = 0.9,
    } = body;

    const isOpenRouterModel = !qwenEnabled || model.endsWith(":free") || model.startsWith("dots-studio/");
    const resolvedKey =
      (isOpenRouterModel
        ? (DIRECT_OPENROUTER_API_KEY || Deno.env.get("OPENROUTER_API_KEY"))
        : (DIRECT_GROQ_API_KEY || Deno.env.get("GROQ_API_KEY"))) ||
      DIRECT_OPENROUTER_API_KEY ||
      DIRECT_GROQ_API_KEY ||
      Deno.env.get("OPENROUTER_API_KEY") ||
      Deno.env.get("GROQ_API_KEY") ||
      (apiKey && apiKey.trim()) ||
      "";

    if (!resolvedKey) {
      return new Response(
        JSON.stringify({ error: { message: "API anahtarı yapılandırılmamış (DIRECT_OPENROUTER_API_KEY / DIRECT_GROQ_API_KEY veya Supabase Secret gerekli)." } }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const useOpenRouter = isOpenRouterModel || resolvedKey.startsWith("sk-or-");
    const endpoint = useOpenRouter
      ? "https://openrouter.ai/api/v1/chat/completions"
      : "https://api.groq.com/openai/v1/chat/completions";

    const targetModel = useOpenRouter && model === "qwen/qwen3.8-27b"
      ? "qwen/qwen3-32b"
      : model;

    const upstreamBody: Record<string, unknown> = {
      model: targetModel,
      messages,
      temperature,
      top_p,
    };

    if (useOpenRouter) {
      upstreamBody.max_tokens = 1600;
    } else {
      upstreamBody.max_completion_tokens = 800;
      upstreamBody.response_format = { type: "json_object" };
    }

    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${resolvedKey}`,
    };
    if (useOpenRouter) {
      headers["HTTP-Referer"] = "https://github.com/Tarkys666/Tarslauncher";
      headers["X-Title"] = "TARS Launcher";
    }

    const upstreamResp = await fetch(endpoint, {
      method: "POST",
      headers,
      body: JSON.stringify(upstreamBody),
    });

    const text = await upstreamResp.text();
    return new Response(text, {
      status: upstreamResp.status,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(
      JSON.stringify({ error: { message: String(err) } }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
