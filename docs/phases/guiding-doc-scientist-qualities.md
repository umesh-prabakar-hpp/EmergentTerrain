# Guiding doc — qualities of a successful scientist (north-star for ET)

A north-star for both the EmergentTerrain research system and the humans building it. EmergentTerrain
exists to do science autonomously; this doc records what *being good at science* actually requires,
in a practitioner's words, and maps each quality onto ET's design so the principles steer real
decisions rather than sitting as a motto.

## Source

Fred Ramsdell — immunologist, **2025 Nobel laureate in Physiology or Medicine** (with Mary Brunkow
and Shimon Sakaguchi, for discoveries concerning peripheral immune tolerance / regulatory T cells).
Asked what makes a scientist successful:

> To be successful as a scientist, I think you really need to be curious. You need to be inquisitive.
> You want to know answers to stuff that you don't have a good sense on. You have to be stubborn
> because you're going to be wrong and you're going to not get through this. You have to have a
> long-term perspective. You can go months, years, sometimes making painfully little progress on
> something – and then something happens and it's exciting. But if you need immediate gratification,
> you should not be a scientist. That's not going to work for you, because there's very little
> immediate gratification in this business. You've got to be stubborn and you've got to have a
> long-term perspective.
>
> Beyond that, it's not that different from many other lines of work. If you want to be good at
> something, you put in the time, you pay attention, you learn the skills, you advance by learning
> and understanding things you didn't know. […] but the inquisitiveness for sure is critical. Being
> a persistent, stubborn person is really important.

## The four qualities — and how ET should embody each

| Quality | What it means | How ET embodies it (and where it falls short) |
|---|---|---|
| **Curiosity / inquisitiveness** | wanting answers to things you have no good sense of; chasing the genuinely unknown, not the safely answerable | ET's *ambition* is discovery, not retrieval — the novelty levers, conjecture generation, and the 30.5/30.6 vocabulary-growth bet. **Shortfall:** the 23.8 calibration found the corpus is 100% known-result verification — ET is currently inquisitive in design but not yet in output. The genuine-curiosity test is whether it pursues *regime transitions*, not new paths in a fixed vocabulary. |
| **Stubbornness / persistence** | you will be wrong, repeatedly; keep going anyway; treat being wrong as part of the work | ET's adversarial loops are institutionalized stubbornness — the dispute/refutation path, the Builder/Breaker-style "try to break it" gates, the intentional re-seed-and-re-evaluate churn ([[feedback_no_reseed_skip]], "do not shrink the opportunities"). Being wrong is *recorded*, not hidden. |
| **Long-term perspective** | months/years of painfully little progress, then something happens; don't optimize for the next step | the autonomous 6h frontier jobs, the anti-stagnation design (CANNOT_COMPUTE *feeds back*, doesn't terminate; OOM/failure persisted as recallable boundary knowledge — [[project_frontier_discovery_feedback_design]]). Progress is allowed to be non-linear and slow. **Cross-run memory (phase-31) is the missing piece** — a long-term perspective requires carryover across room resets ([[project_room_reset_on_restart]]). |
| **No need for immediate gratification** | there is very little; don't chase quick, gratifying signals | the cleanest design echo: the **MDL-on-widening-evidence guard** — accept on paired compression over a *widened* evidence set, never on a quick monotone metric bump (a falling R² under harder evidence is *correct*). ET must not reward gratifying-but-shallow wins (a high R² on easy data, a fast panel pass). See [phase-30-conjecture/phase-30-categorical-patterns.md](phase-30-conjecture/phase-30-categorical-patterns.md). |

## The plain part Ramsdell adds

> *"it's not that different from many other lines of work… put in the time, pay attention, learn the
> skills, advance by learning and understanding things you didn't know."*

A useful counterweight to over-romanticizing discovery: most of the value is craft and accumulation,
not eureka. For ET this is the unglamorous infrastructure — provenance discipline, the validator
ladder, gates, memory — that makes the rare exciting result trustworthy when it comes. Build the
craft; the discovery is downstream of it.

## How to apply this

**For the system (design lens).** When a design choice trades long-term capability for a quick
metric win, this doc says no: prefer the gate that rewards compression-under-pressure over the one
that rewards monotone improvement; prefer feeding failure back over terminating on it; prefer
persistence (re-seed churn) over premature pruning; treat "we were wrong" as a first-class,
recorded outcome. The genuine-curiosity bar — pursue *discovery* (vocabulary growth), not just
*search* — is the standing aspiration, with [phase-30-conjecture/generation-engine-library-learning.md](phase-30-conjecture/generation-engine-library-learning.md)
the queued route to it.

**For contributors.** The same: long-horizon work over immediate gratification, stubbornness through
being wrong, curiosity about what you don't yet understand. Don't let painfully-slow stretches read
as failure — they are the normal texture of the work.
