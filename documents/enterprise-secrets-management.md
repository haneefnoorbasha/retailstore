# Enterprise Secrets Management — HashiCorp Vault

> **Who is this for?** Understanding how real enterprises like Walmart, Target, and JPMorgan
> manage secrets for CI/CD pipelines — specifically how Jenkins authenticates to Vault and
> retrieves credentials without any human involvement or static secrets.

---

## The Core Problem

In your local setup, you stored a GitHub PAT directly in Jenkins. In enterprise this is
forbidden because:

- Static secrets never expire — if leaked, attacker has permanent access
- No audit trail of who used the secret and when
- Rotation requires manual updates across every system that uses it
- Security scans flag long-lived static credentials immediately

**The enterprise answer: HashiCorp Vault**

---

## What is HashiCorp Vault?

A dedicated secrets server. Applications never store secrets — they ask Vault for a
short-lived credential at runtime, use it, and it expires automatically.

```
Without Vault:                      With Vault:
──────────────                      ────────────
Jenkins stores GitHub token    →    Jenkins stores nothing
Token lives forever            →    Token expires in 15 minutes
Leaked token = permanent breach →   Leaked token = already expired
Manual rotation                →    Automatic rotation
No audit trail                 →    Every access logged with who/when/why
```

---

## Example: Jenkins Pulling a GitHub Token from Vault

This is the most common real-world use case — Jenkins needs to clone a private GitHub
repo and push Helm commits back. Here is the complete step-by-step flow.

### The Players

| Component | What it is |
|---|---|
| **Vault server** | Central secrets server — runs on a dedicated VM or K8s pod |
| **Jenkins** | CI/CD server — needs GitHub token to clone repo and push commits |
| **GitHub** | Source code host — requires authentication |
| **AppRole** | Vault's machine identity for Jenkins (like a service account) |

---

## One-Time Setup (Done by Platform/EDP Team)

### Step 1 — Store the GitHub token in Vault

The EDP team creates a GitHub App or machine account token and stores it in Vault.
This is the only time a human touches the secret.

```bash
# EDP engineer runs this once
vault kv put secret/cicd/github \
  token="ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
```

Vault stores it encrypted at rest. No human sees it again after this point.

---

### Step 2 — Create a Vault Policy for Jenkins

A policy defines exactly what Jenkins is allowed to read — nothing more.

```hcl
# File: jenkins-policy.hcl

# Jenkins can only READ the GitHub token — cannot write, list, or delete
path "secret/data/cicd/github" {
  capabilities = ["read"]
}

# Jenkins can also read ECR credentials if needed
path "secret/data/cicd/ecr" {
  capabilities = ["read"]
}
```

```bash
vault policy write jenkins-policy jenkins-policy.hcl
```

---

### Step 3 — Create an AppRole for Jenkins

AppRole is Vault's authentication method for machines and applications.
It works like a username (RoleID) + password (SecretID), but the SecretID
expires after one use or after a short time window.

```bash
# Enable AppRole auth method (one-time)
vault auth enable approle

# Create the Jenkins AppRole — bind it to the policy above
vault write auth/approle/role/jenkins \
  token_policies="jenkins-policy" \
  token_ttl=15m \          # token Jenkins gets is valid for 15 minutes only
  token_max_ttl=30m \      # hard ceiling — cannot be renewed beyond 30 min
  secret_id_ttl=10m \      # SecretID (the "password") expires in 10 min
  secret_id_num_uses=1     # SecretID can only be used ONCE — prevents replay attacks

# Get the RoleID (this is like a username — not secret, can be stored in Jenkins)
vault read auth/approle/role/jenkins/role-id
# Output: role_id = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
```

The **RoleID** is stored in Jenkins (it's not secret — useless without the SecretID).
The **SecretID** is never stored anywhere — it is generated fresh for every pipeline run.

---

### Step 4 — Vault Agent runs as a sidecar on Jenkins

In enterprise, a **Vault Agent** runs alongside Jenkins (as a sidecar container or
system process). Its only job is to generate fresh SecretIDs when Jenkins needs them.

```
Jenkins VM / Pod
├── Jenkins process
└── Vault Agent sidecar
    - Authenticates to Vault using the EC2 IAM role (or K8s service account)
    - Generates a fresh SecretID on demand
    - Writes it to a local socket/file that only Jenkins can read
    - SecretID expires in 10 min and after 1 use
```

Vault Agent itself authenticates to Vault using the underlying platform identity
(EC2 IAM role, K8s ServiceAccount, etc.) — not another static secret. This breaks
the "secret zero" problem: there is no root credential to protect.

---

## Runtime Flow — What Happens on Every Pipeline Run

```
Developer pushes code to GitHub
            │
            ▼
    GitHub webhook fires
            │
            ▼
┌─────────────────────────────────────────────────────────┐
│  JENKINS PIPELINE STARTS                                │
│                                                         │
│  Stage: Checkout                                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │  1. Pipeline calls Vault Agent sidecar           │   │
│  │     "I need a SecretID for the jenkins AppRole"  │   │
│  │                          │                       │   │
│  │  2. Vault Agent → Vault Server                   │   │
│  │     POST /auth/approle/role/jenkins/secret-id    │   │
│  │     Response: secret_id = "abc123" (expires 10m) │   │
│  │                          │                       │   │
│  │  3. Pipeline sends RoleID + SecretID to Vault    │   │
│  │     POST /auth/approle/login                     │   │
│  │     { role_id: "xxx", secret_id: "abc123" }      │   │
│  │                          │                       │   │
│  │  4. Vault validates and returns a Vault Token     │   │
│  │     vault_token = "s.xxxxxxxxxxxxxxxx"           │   │
│  │     TTL: 15 minutes — pipeline must finish in 15m│   │
│  │                          │                       │   │
│  │  5. Pipeline uses Vault Token to read secret     │   │
│  │     GET /secret/data/cicd/github                 │   │
│  │     Header: X-Vault-Token: s.xxxxxxxxxxxxxxxx    │   │
│  │     Response: { token: "ghp_xxxx..." }           │   │
│  │                          │                       │   │
│  │  6. Pipeline uses token to clone GitHub repo     │   │
│  │     git clone https://ghp_xxxx@github.com/...   │   │
│  │                          │                       │   │
│  │  7. Pipeline finishes — Vault Token auto-expires │   │
│  │     The secret never touched disk                │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  Stage: Docker Build & Push                             │
│  Stage: Deploy (ArgoCD / Helm)                          │
└─────────────────────────────────────────────────────────┘
```

---

## What the Jenkinsfile Looks Like With Vault

Jenkins has a HashiCorp Vault plugin. Once configured, the Jenkinsfile uses it like this:

```groovy
pipeline {
    agent any

    environment {
        VAULT_ADDR = 'https://vault.internal.walmart.com'
        ROLE_ID    = credentials('vault-jenkins-role-id')  // not secret — stored in Jenkins
    }

    stages {
        stage('Checkout') {
            steps {
                // Vault plugin fetches SecretID → exchanges for token → reads secret
                // All in one call — developer writes none of the auth logic
                withVault(
                    vaultSecrets: [[
                        path:        'secret/cicd/github',
                        engineVersion: 2,
                        secretValues: [[
                            vaultKey:    'token',
                            envVar:      'GITHUB_TOKEN'
                        ]]
                    ]]
                ) {
                    // GITHUB_TOKEN is available here — in memory only
                    sh 'git clone https://${GITHUB_TOKEN}@github.com/walmart/retailstore.git'
                }
                // GITHUB_TOKEN is gone here — out of scope, never written to disk
            }
        }

        stage('Docker Build & Push') {
            steps {
                withVault(
                    vaultSecrets: [[
                        path: 'secret/cicd/ecr',
                        secretValues: [[vaultKey: 'password', envVar: 'ECR_PASSWORD']]
                    ]]
                ) {
                    sh 'echo $ECR_PASSWORD | docker login --username AWS --password-stdin $ECR_REGISTRY'
                }
            }
        }
    }
}
```

The Vault plugin handles the entire AppRole flow (steps 1–5 above) transparently.
The pipeline developer only declares **what secret they need** — not how to fetch it.

---

## Audit Trail — What Vault Logs for Every Access

Every secret read is logged in Vault's audit log:

```json
{
  "time":      "2026-06-25T09:14:23Z",
  "type":      "response",
  "auth": {
    "display_name": "approle:jenkins",
    "policies":     ["jenkins-policy"],
    "token_ttl":    900
  },
  "request": {
    "operation": "read",
    "path":      "secret/data/cicd/github",
    "remote_address": "10.0.1.45"
  }
}
```

Security team can answer: **who read what secret, when, from which IP, for which pipeline.**
With static secrets in Jenkins, none of this is possible.

---

## Vault vs AWS Secrets Manager — When to Use Which

| | HashiCorp Vault | AWS Secrets Manager |
|---|---|---|
| **Best for** | Multi-cloud, on-prem, non-AWS secrets | AWS-only shops |
| **Dynamic secrets** | Yes — generates DB passwords, SSH keys, PKI certs on demand | Limited |
| **Secret zero solution** | Vault Agent + platform identity (IAM, K8s SA) | IAM Role (EC2/EKS) |
| **Used by** | Walmart, Target, JPMorgan, Goldman Sachs | Amazon-internal teams, AWS-native startups |
| **Cost** | Open source (free) + Enterprise license for large teams | $0.40/secret/month + API calls |

For your RetailStore project on AWS: **AWS Secrets Manager** is the natural choice for
stage/prod. Vault is worth understanding because most large enterprises use it —
especially those that were on-prem before moving to cloud.

---

## How Your RetailStore Would Look With Vault (Future State)

```
Current (local learning)        Enterprise (future)
────────────────────────        ───────────────────
GitHub PAT → Jenkins creds  →   GitHub App token → Vault → Jenkins (runtime only)
~/.aws mounted in container →   EC2 IAM Role (no keys, no mount needed)
ECR password via aws CLI    →   Same (IAM Role is already the enterprise pattern)
```

Your ECR authentication is already enterprise-grade. The only gap is the GitHub token.

---

## Key Takeaways

1. **Vault is a secrets server** — applications authenticate to Vault, not directly to the target service
2. **AppRole** = machine identity for Jenkins (RoleID is not secret; SecretID is one-time use)
3. **Vault Agent sidecar** solves the "secret zero" problem — uses platform identity (IAM Role, K8s ServiceAccount) so there is no root credential to protect
4. **Every secret access is audited** — who, what, when, from where
5. **Secrets live in memory only** — never written to disk, never in git, never in Jenkins UI
6. **Your ECR flow is already correct** — `aws ecr get-login-password` via IAM Role is exactly this pattern applied to AWS
