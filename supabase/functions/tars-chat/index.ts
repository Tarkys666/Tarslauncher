import "jsr:@supabase/functions-js/edge-runtime.d.ts";

/**
 * TARS Launcher - AI Chat Proxy Edge Function
 * Routes client prompts to OpenRouter (`dots-studio/dots-3-note-preview:free`) or Groq (`qwen/qwen3.8-27b`).
 * Client passes { model, messages, apiKey, qwenEnabled, temperature, top_p }.
 * Uses client-provided key, or falls back to backend secrets (OPENROUTER_API_KEY / GROQ_API_KEY).
 */

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
    const resolvedKey = (apiKey && apiKey.trim()) ||
      (isOpenRouterModel ? Deno.env.get("OPENROUTER_API_KEY") : Deno.env.get("GROQ_API_KEY")) ||
      Deno.env.get("OPENROUTER_API_KEY") ||
      Deno.env.get("GROQ_API_KEY") ||
      "";

    if (!resolvedKey) {
      return new Response(
        JSON.stringify({ error: { message: "API anahtarı bulunamadı (Supabase Secret veya istemci anahtarı gerekli)." } }),
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
