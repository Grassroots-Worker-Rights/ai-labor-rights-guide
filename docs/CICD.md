# CI/CD — build images in GitHub Actions, deploy to one server (stage + prod)

## How it fits together

```
Angular-frontend repo ──push main──▶ frontend-ci.yml ─┐
                                                      ├─▶ ghcr.io/grassroots-worker-rights/ai-labor-rights-{frontend,backend}
ai-labor-rights-guide ──push master─▶ backend-ci.yml ──┘

ai-labor-rights-guide ──manual──▶ deploy.yml (stage | prod, tags) ──scp + ssh──▶ server
```

- **CI (each repo):** test → build & push image → if conventional commits bump the version, create the
  `vX.Y.Z` git tag + GitHub Release. The tag is created only after the image exists. Pull requests
  run the tests only.
- **Test images from any branch:** Actions → *Frontend CI* / *Backend CI* → *Run workflow* → pick the
  branch. Builds a test image, never a release.

| Built from | Tags | Prod-ready? |
|---|---|---|
| `main` / `master` (push or manual) | `sha-<short>` always; `vX.Y.Z` + `latest` when the version bumps | only `vX.Y.Z` |
| manual run on another branch | `dev-<branch>-<short>` (exact commit), `dev-<branch>` (moves) | no — stage only |

  The deploy workflow refuses any non-`vX.Y.Z` tag for prod.
- **Deploy (backend repo, manual):** choose `stage` or `prod` and optionally the two image tags.
  Empty tag = latest GitHub Release of that repo. The workflow checks the images exist in GHCR,
  copies the compose file (prod: also `nginx/templates/` and `scripts/init-ssl.sh`) to the server,
  runs `docker compose pull && up -d`, and waits for the backend to answer.
- **No git on the server.** Persistent server-side state is only `.env`, `.env.stage`, `models/`, `certbot/`.

### Versioning (commit prefixes)

| Commit                          | Bump  |
|---------------------------------|-------|
| `feat: …` / `feature: …`        | minor |
| `fix:` `bugfix:` `perf:` `refactor:` | patch |
| `major: …` / `breaking: …`      | major |
| anything else (`chore:`, `docs:` …) | none — only a `sha-<short>` image is pushed |

## Server layout

```
/opt/apps/ai-labor-rights/
├── .env                     # prod secrets — manual
├── .env.stage               # stage secrets — manual
├── models/                  # ONNX embedding model, shared read-only by both stacks — manual
├── certbot/{conf,www}/      # one certificate for both domains — managed by prod's certbot
├── docker-compose.prod.yml  # ← copied by deploy (prod)
├── docker-compose.stage.yml # ← copied by deploy (stage)
├── nginx/templates/         # ← copied by deploy (prod)
└── scripts/init-ssl.sh      # ← copied by deploy (prod)
```

Two compose projects, `ai-labor-rights-prod` and `ai-labor-rights-stage`, each with its own volumes.
Only prod's nginx publishes ports (80/443). It serves both `DOMAIN` and `STAGE_DOMAIN` with one
certificate, and reaches the stage containers (`stage-backend`, `stage-frontend`) over the shared
Docker network `ai-labor-rights-edge`. Stage has no nginx, certbot or ports of its own.

## One-time setup

### GitHub

1. **Secrets** (backend repo → Settings → Secrets and variables → Actions):
   - `SSH_HOST`, `SSH_USER`
   - `SSH_PRIVATE_KEY` (recommended) — or `SSH_PASSWORD`
   - `SSH_PORT` (optional, default 22)
   - `GHCR_TOKEN` (both repos, **only if the repos are not owned by the Grassroots-Worker-Rights
     org**): classic PAT of an org member with `write:packages`. If the repos live in the org, the
     built-in `GITHUB_TOKEN` is enough.
2. **Variables** (optional): `IMAGE_NAMESPACE` (default `grassroots-worker-rights`, must be lowercase),
   `FRONTEND_REPO` (default `<owner>/Angular-frontend`).
3. **Environments** (backend repo → Settings → Environments): `stage` and `prod` are created on the
   first run. Add a *required reviewer* to `prod` so production deploys need approval.
4. **Package visibility:** after the first CI run, check that both packages under
   *Grassroots-Worker-Rights → Packages* are **Public**. GHCR may create new packages as private,
   and the server pulls without logging in.

### Server

1. DNS: `stage.ergasiakos-odigos.gr` → A record to the same IP as the prod domain.
2. Add to **prod** `.env`:
   ```
   STAGE_DOMAIN=stage.ergasiakos-odigos.gr
   ```
   (`.env.stage` can keep its `DOMAIN=` line; stage doesn't use it.)
3. Shared network (the deploy workflow also creates it if missing):
   ```bash
   docker network create ai-labor-rights-edge
   ```
4. Certificate, one of:
   - **Fresh server, no certificate yet:** deploy prod once (to get the files on the server), then
     `cd /opt/apps/ai-labor-rights && bash scripts/init-ssl.sh` (it reads `DOMAIN`, `STAGE_DOMAIN`, `EMAIL` from `.env`).
   - **Add the stage domain to the existing certificate:** deploy prod first (port 80 must
     answer for the stage name), then:
     ```bash
     cd /opt/apps/ai-labor-rights
     C="docker compose -f docker-compose.prod.yml --env-file .env"
     $C run --rm --entrypoint certbot certbot certonly --webroot -w /var/www/certbot \
       --cert-name ergasiakos-odigos.gr -d ergasiakos-odigos.gr -d stage.ergasiakos-odigos.gr \
       --expand --non-interactive --agree-tos -m <EMAIL>
     $C exec nginx nginx -s reload
     ```
     Use the name of the existing folder under `certbot/conf/live/` as `--cert-name`. It must equal prod's `DOMAIN`.

## Day to day

- **Release:** merge to `master` / `main` with a `feat:` or `fix:` commit → CI publishes `vX.Y.Z`.
- **Deploy to stage:** Actions → *Deploy* → `stage`. Leave the tags empty for the latest releases, or
  give `sha-1a2b3c4` (unreleased main/master commit) or `dev-<branch>` / `dev-<branch>-<short>` (a
  manual branch build — the exact tags are in that CI run's summary).
- **Promote to prod:** run *Deploy* → `prod` with the same tags you verified on stage.
- **Rollback:** run *Deploy* again with the previous tags.
- **Index content after the first deploy of an environment** (or after content changes):
  ```bash
  cd /opt/apps/ai-labor-rights
  # prod
  docker compose -f docker-compose.prod.yml --env-file .env exec meilisearch \
    curl -fsS -X POST http://backend:8080/api/admin/ingest
  # stage
  docker compose -f docker-compose.stage.yml --env-file .env.stage exec meilisearch \
    curl -fsS -X POST http://backend:8080/api/admin/ingest
  ```

## Gotchas

- **Memory:** both stacks share one box. Prod caps ≈ 6.0 GB (Meili 2 G, Chroma 1 G, backend 2.5 G,
  nginx/frontend/certbot ≈ 0.45 G), stage ≈ 2.7 GB. Check `free -h` on the server; on 8 GB, lower the
  limits or stop stage when you don't need it (`docker compose -f docker-compose.stage.yml --env-file .env.stage down`).
- **nginx changes ship only with a prod deploy**, including `stage.conf.template`, because prod's nginx
  serves stage too.
- Stage returns **502** while the stage stack is down. Prod is unaffected.
