# Public Export Setup

The private repository is the canonical source of truth.

## Local remotes

```bash
git remote set-url origin git@github.com:sigilaris/sigilaris-private.git
git remote add public git@github.com:sigilaris/sigilaris.git
```

`origin` is for day-to-day development.

`public` exists only for inspection and emergency manual checks. Do not develop against it.

## Export workflow

The private repository runs `.github/workflows/export-public.yml`.

It exports tracked files from `main`, removes paths listed in `.public-export-ignore`, and synchronizes the resulting snapshot into the public repository's `main` branch.

Current private-only paths:

- `.github/workflows/export-public.yml`
- `.public-export-ignore`
- `scripts/export-public.sh`
- `docs/dev`
- `benchmarks/reports`
- `*/src/test`

## Required GitHub secret

Configure this secret in `sigilaris-private`:

- `PUBLIC_REPO_PUSH_TOKEN`: fine-grained PAT with `Contents: Read and write` on `sigilaris/sigilaris`
- If the token will update `.github/workflows/site.yml` too, also grant `Workflows: Read and write`

## Notes

- `site.yml` is guarded so it only executes in the public repository.
- Public repository changes should come from the export workflow, not direct commits.
