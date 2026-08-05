# monitoring-control-system

This is a [Next.js](https://nextjs.org) project bootstrapped with [v0](https://v0.app).

> ⚠️ **배포는 v0 가 하지 않는다** (2026-08-05 확인). 아래 "Every merge to `main` will
> automatically deploy" 는 v0 템플릿 문구다. 실제 배포는 **`develop` 푸시 → GitLab CI
> (`.gitlab-ci.yml` → `scripts/deploy-ec2.sh`) → EC2 Docker Compose** 이고, 화면은
> `http://i15a304.p.ssafy.io:3000` 에서 돈다. 절차는 `docs/deploy/ec2-auto-deploy.md`
> (Jira S15P11A304-179·182).
>
> 이 레포의 기본 브랜치는 `main` 이 아니라 **`master`** 이고, 실작업은 `develop` 이다.

## Built with v0

This repository is linked to a [v0](https://v0.app) project. You can continue developing by visiting the link below -- start new chats to make changes, and v0 will push commits directly to this repo. Every merge to `main` will automatically deploy.

[Continue working on v0 →](https://v0.app/chat/projects/prj_DRARCIYWV9oimisDAkCvWZR66LTk)

## Getting Started

First, run the development server:

```bash
npm run dev
# or
yarn dev
# or
pnpm dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

You can start editing the page by modifying `app/page.tsx`. The page auto-updates as you edit the file.

## Learn More

To learn more, take a look at the following resources:

- [Next.js Documentation](https://nextjs.org/docs) - learn about Next.js features and API.
- [Learn Next.js](https://nextjs.org/learn) - an interactive Next.js tutorial.
- [v0 Documentation](https://v0.app/docs) - learn about v0 and how to use it.
