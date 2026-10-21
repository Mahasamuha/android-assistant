# Skills

Instruction bundles for the assistant, in the Agent Skills `SKILL.md` format (plan §4b). One folder per skill:

```
skills/<name>/SKILL.md
```

`SKILL.md` is YAML frontmatter followed by plain instructions for the model:

```yaml
---
name: weather                 # unique; a server draft with the same name overrides the bundled skill
description: One line on what it's for and when to use it.
delivery: app                 # app = bundled into the APK at build time; server = draft, served by the server (§4b step 4)
tools: [get_weather]          # tools it relies on; omit for instruction-only skills
---
Instructions...
```

Frontmatter is a small YAML subset: `key: value` lines, and `tools` as a `[a, b]` list.

Rules:
- A skill is included only when every tool it lists is available in that session.
- Skills can't change confirmation behavior: that's enforced per tool in the app.
- Write for the ear: the base prompt already covers brevity and plain text, so a skill only adds what's specific to its domain.
