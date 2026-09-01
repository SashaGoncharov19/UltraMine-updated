# The autonomous loop

Four workflows form a loop from an issue to a reviewed pull request. This
document says what each does, what stops it, and what it deliberately does not
do.

## The loop

```
   an issue                          claude-fix-issue.yml
      │                                 plan → branch → gates → PR
      ▼
   a pull request  ──────────────►   claude-review-pr.yml
      ▲                                 verdict + risk 0-10
      │                                 approve │ request changes
      │                                         │
      └──────────────────────────    claude-address-review.yml
                                        fix │ disagree │ split out
                                        push + reply

   build.yml is not part of the loop. It is the thing the loop answers to.
```

| Workflow | Starts from | Produces | Never |
|---|---|---|---|
| `claude-fix-issue.yml` | `claude:fix` label, or dispatch | a plan comment, a branch, a pull request | merges |
| `claude-review-pr.yml` | a pull request, `claude:review`, or dispatch | one review: verdict, risk, findings | merges, or approves what it cannot defend |
| `claude-address-review.yml` | a review requesting changes on a Claude-authored PR, or dispatch | commits on that branch, one reply | merges, pushes to `dev`/`master`, or dismisses the review |
| `build.yml` | every push; modpack boot by dispatch | the evidence everything else is judged against | anything automatic |

## What actually anchors this

**Not the review.** A model reviewing its own work converges on agreement with
itself, which is not the same as correctness. The review is useful for what it
reads - a jar's contents, a call path, a version's real behaviour - not for its
opinion.

The anchors are the gates, because they can say no:

- `./gradlew test` - runs on every push, and the reviewer runs it again itself
  rather than trusting the author
- the Java 8 and Java 25 boot smoke tests
- the release-pack modpack boot, dispatched by hand, on the pack people download

Every one of the Java 25 blockers this loop has fixed was found by a gate, not by
a review. When a change cannot be judged by a gate, that is worth saying out loud
rather than compensating for with a more confident review.

## What stops it

- **Attempt budget.** Four rounds of automated changes on one pull request, then
  `claude-address-review.yml` comments and hands over. Past that, another attempt
  churns rather than converges, and the question is usually whether the approach
  is right rather than whether the patch is.
- **Merging is not delegated.** No workflow merges, and none enables auto-merge.
  An approval is advice; it may satisfy a branch protection rule, which is why
  the review prompt says to give one only where it would defend the change.
- **The action refuses pull requests that edit workflow files.** That is upstream
  behaviour, not ours. The gates report it as a notice and the change is reviewed
  by hand.
- **Every run must leave evidence.** A finished run is not a done job: the issue
  workflow requires a comment, the review workflow requires a review, and the
  address workflow requires a push or a reply. This exists because a run once
  finished green having posted nothing at all.
- **Only Claude's own pull requests are answered automatically.** A person's pull
  request is theirs; dispatch by hand to put Claude on one.

## What it does not do

- It does not decide what to work on. Issues come from a person.
- It does not merge, release, or tag.
- It does not touch `dev` or `master` directly.
- It does not judge performance. An optimisation without a measurement is not a
  fix, and none of these workflows can measure one - #19 tracks the harness that
  would.
- It does not replace reading the diff. The risk score is an argument, not a
  verdict.

## Using it

Give it an issue worth solving. The issue is the input, and a vague issue
produces a vague pull request - the ones that have gone well here named the file,
the symptom, and what would count as done.

```
# put Claude on an issue
gh issue edit <n> --add-label claude:fix

# review a pull request that predates the workflow, or re-review one
gh workflow run claude-review-pr.yml -f pr=<n>

# answer a review yourself, out of band
gh workflow run claude-address-review.yml -f pr=<n> -f extra="..."
```

Both `claude-fix-issue.yml` and `claude-review-pr.yml` take an `extra` input.
It is the highest-leverage thing available: scoping a run to one checkbox, or
naming the thing to check first, is most of the difference between a useful run
and a wandering one.
