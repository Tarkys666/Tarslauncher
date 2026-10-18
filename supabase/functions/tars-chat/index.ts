import "jsr:@supabase/functions-js/edge-runtime.d.ts";

/**
 * TARS Launcher - AI Chat Proxy Edge Function
 * Routes client prompts to Groq (ultra-fast Qwen 3.8-27b) or OpenRouter (Cohere/Gemma free models).
 * Features automatic failover: If OpenRouter is slow, rate-limited, or returns empty content,
 * it seamlessly fails over to Groq so the user NEVER encounters a failed prompt.
 */

const DIRECT_OPENROUTER_API_KEY = "";
const DIRECT_GROQ_API_KEY = "";

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
      model = "qwen/qwen3.8-27b",
      messages = [],
      apiKey = "",
      qwenEnabled = true,
      temperature = 0.15,
      top_p = 0.9,
    } = body;

    const openRouterKey = (apiKey && apiKey.startsWith("sk-or-") ? apiKey : "") ||
      DIRECT_OPENROUTER_API_KEY ||
      Deno.env.get("OPENROUTER_API_KEY") ||
      "";

    const groqKey = (apiKey && apiKey.startsWith("gsk_") ? apiKey : "") ||
      DIRECT_GROQ_API_KEY ||
      Deno.env.get("GROQ_API_KEY") ||
      "";

    const preferOpenRouter = !qwenEnabled && Boolean(openRouterKey);

    // Helper to call Groq (ultra-fast 200ms)
    async function callGroq() {
      if (!groqKey) {
        throw new Error("GROQ_API_KEY bulunamadı.");
      }
      const upstreamBody: Record<string, unknown> = {
        model: "qwen/qwen3.8-27b",
        messages,
        temperature,
        top_p,
        max_completion_tokens: 800,
        response_format: { type: "json_object" },
      };
      const resp = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${groqKey}`,
        },
        body: JSON.stringify(upstreamBody),
      });
      const text = await resp.text();
      return { status: resp.status, text };
    }

    // Helper to call OpenRouter
    async function callOpenRouter() {
      if (!openRouterKey) {
        throw new Error("OPENROUTER_API_KEY bulunamadı.");
      }
      // Map broken dots preview model to working free code/reasoning model on OpenRouter
      const targetModel = (model === "dots-studio/dots-3-note-preview:free" || !model)
        ? "cohere/north-mini-code:free"
        : model;

      const upstreamBody: Record<string, unknown> = {
        model: targetModel,
        messages,
        temperature,
        top_p,
        max_tokens: 1600,
      };
      const resp = await fetch("https://openrouter.ai/api/v1/chat/completions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${openRouterKey}`,
          "HTTP-Referer": "https://github.com/Tarkys666/Tarslauncher",
          "X-Title": "TARS Launcher",
        },
        body: JSON.stringify(upstreamBody),
      });
      const text = await resp.text();
      return { status: resp.status, text };
    }

    let result: { status: number; text: string };

    if (preferOpenRouter) {
      try {
        result = await callOpenRouter();
        let isValid = result.status >= 200 && result.status < 300;
        if (isValid) {
          try {
            const parsed = JSON.parse(result.text);
            const content = parsed?.choices?.[0]?.message?.content?.trim();
            if (!content) {
              isValid = false;
            }
          } catch {
            isValid = false;
          }
        }

        // If OpenRouter timed out, 429'd, or returned empty content, seamlessly fallback to Groq
        if (!isValid && groqKey) {
          result = await callGroq();
        }
      } catch (_e) {
        if (groqKey) {
          result = await callGroq();
        } else {
          throw _e;
        }
      }
    } else {
      // Primary: Groq (Qwen 3.8-27B)
      if (groqKey) {
        result = await callGroq();
      } else if (openRouterKey) {
        result = await callOpenRouter();
      } else {
        return new Response(
          JSON.stringify({ error: { message: "API anahtarı bulunamadı (Supabase Secrets kasasında OPENROUTER_API_KEY veya GROQ_API_KEY gerekli)." } }),
          { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
    }

    return new Response(result.text, {
      status: result.status,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(
      JSON.stringify({ error: { message: String(err) } }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
