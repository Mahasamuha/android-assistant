> **Disclaimer:** This repository is largely LLM-generated code that has not yet been reviewed. Treat it as a work in progress, not as a reference implementation.

# Phone Assistant

A personal voice assistant for Android that replaces Google Assistant on a single Pixel phone. The app holds the default assistant role, so a power-button long-press or corner swipe opens its panel. It listens with on-device speech recognition, sends the question to a hosted LLM (Anthropic's Claude), and speaks the answer with on-device text-to-speech. Live answers come from the provider's web search and from tools that run on the phone, such as a weather lookup. Requests go through a small self-hosted auth gateway that checks identity-provider tokens and forwards to a private LLM gateway (Bifrost) that enforces a spend budget, so no provider keys ever live on the phone. See `docs/plan.md` for the design and its rationale.
