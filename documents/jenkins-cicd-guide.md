# Jenkins CI/CD Guide

Complete reference for Jenkins — local setup, credentials, pipeline mechanics, how each stage works, environment variables, ECR authentication, GitOps integration, and EC2 production setup. Uses `api-gateway` as the worked example throughout.

---

## Enterprise Context — Who Owns What

In companies like Walmart, JPMorgan, and Target, Jenkins is **shared infrastructure owned by a central Platform / EDP (Enterprise DevOps Platform) team**. Individual service teams never provision the Jenkins server.

| Who | Owns |
|---|---|
| **Your team (service team)** | Jenkinsfiles, ECR repos (Terraform), Helm charts, ArgoCD manifests |
| **EDP / Platform team** | Jenkins server, plugins, agent pool, Jenkins upgrades |

**For this learning project:** you are playing all roles, so this guide covers everything including EC2 provisioning via Terraform (Part 9). In a real company, Part 9 is done by EDP — not the service team.

---

## Part 1 — Local Jenkins (Docker Desktop)

Jenkins runs as a **standalone Docker container** — separate from the application `docker-compose` stack. It is not part of the microservices; it is infrastructure that builds and deploys the microservices.

### Start

```bash
cd retailstore-infra
./scripts/start-jenkins.sh
```

- First run builds the custom image (~5 min to download plugins)
- Subsequent starts reuse the cached image and resume in seconds
- Jenkins UI: http://localhost:8090
- All data persisted in `jenkins_home` Docker volume — survives restarts and Mac reboots

### Stop

```bash
cd retailstore-infra
./scripts/stop-jenkins.sh
```

### Useful commands

```bash
# Get initial admin password (first run only)
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword

# Stream Jenkins logs
docker logs -f jenkins

# Wipe everything and start fresh
docker volume rm jenkins_home
```

### What is inside the Jenkins Docker image

The custom image (`retailstore-infra/jenkins/Dockerfile`) includes:

| Tool | Why it is there |
|---|---|
| `jenkins/jenkins:lts-jdk21` | Base Jenkins with Java 21 |
| Docker CLI | So Jenkinsfiles can run `docker build` and `docker push` |
| AWS CLI v2 | For `aws ecr get-login-password` to authenticate with ECR |
| Maven 3.9.9 | For `mvn test` when unit tests are added later |
| Pre-installed plugins | Pipeline, Git, GitHub, Docker Pipeline, AWS Credentials, JaCoCo, JUnit, Credentials Binding, and others |

---

## Part 2 — First-Time Setup Wizard

Do this once after the very first `start-jenkins.sh`.

1. Open http://localhost:8090
2. Paste the initial admin password: `docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword`
3. **Customize Jenkins** → click **"Select plugins to install"** → click **"None"** (deselect all) → **Install**
   > Plugins are pre-installed in the Docker image. Do NOT install the suggested set — it adds duplicates.
4. **Create First Admin User** → fill in username, password, email → **Save and Continue**
5. **Instance Configuration** → leave URL as `http://localhost:8090/` → **Save and Finish**
6. Click **"Start using Jenkins"**

---

## Part 3 — Add Credentials

Jenkins stores secrets in an **encrypted credential store** — secrets are referenced by a short ID in Jenkinsfiles. The actual values never appear in code or git.

**Navigate to:** Manage Jenkins → Credentials → System → Global credentials → Add Credentials

### 3a — ECR Registry URI

| Field | Value |
|---|---|
| Kind | Secret text |
| ID | `ecr-registry` |
| Secret | `067744548987.dkr.ecr.us-east-1.amazonaws.com` |
| Description | ECR registry URI |

Get this from `terraform apply` output in `retailstore-infra/terraform/ecr/` (the `registry_uri` output).

### 3b — GitHub Token

| Field | Value |
|---|---|
| Kind | Secret text |
| ID | `github-token` |
| Secret | Your GitHub Personal Access Token |
| Description | GitHub PAT — Jenkins uses this to push Helm values commits |

**Create the token:**
1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token
2. Scopes: `repo` (full) + `workflow`
3. Copy immediately — GitHub shows it only once

### 3c — AWS Credentials (local Mac Jenkins only)

> **Skip this for EC2 Jenkins** — the EC2 IAM Role (provisioned by Terraform) gives Jenkins automatic AWS access.

For local Jenkins on Mac, if `aws configure` is already set up on your Mac, the AWS CLI inside the Jenkins container may inherit those credentials via the Docker socket. Try the pipeline first — it may work without this step.

If it does not, create an IAM user with ECR push permissions and add:

| Field | Value |
|---|---|
| Kind | AWS Credentials |
| ID | `aws-jenkins` |
| Access Key ID | From the IAM user |
| Secret Access Key | From the IAM user |

---

## Part 4 — Create Pipeline Jobs

One Pipeline job per service (6 total). Repeat for each.

**Navigate to:** Dashboard → New Item

| Service | Job Name | Jenkinsfile Path |
|---|---|---|
| catalog-service | `catalog-pipeline` | `catalog-service/Jenkinsfile` |
| cart-service | `carts-pipeline` | `cart-service/Jenkinsfile` |
| checkout-service | `checkout-pipeline` | `checkout-service/Jenkinsfile` |
| order-service | `orders-pipeline` | `order-service/Jenkinsfile` |
| experience-service | `experience-pipeline` | `experience-service/Jenkinsfile` |
| api-gateway | `gateway-pipeline` | `api-gateway/Jenkinsfile` |

### Steps for each job

1. **New Item** → enter job name → select **Pipeline** → OK
2. **General** → check **"Discard old builds"** → Max builds to keep: `10`
3. **Build Triggers** → check **"GitHub hook trigger for GITScm polling"**
4. **Pipeline** section:
   - Definition: **Pipeline script from SCM**
   - SCM: **Git**
   - Repository URL: `https://github.com/<your-username>/retailstore.git`
   - Branch: `*/main`
   - Script Path: `api-gateway/Jenkinsfile` ← change per job
5. **Save**

---

## Part 5 — GitHub Webhook Setup

A webhook makes GitHub notify Jenkins on every push to `main`. All 6 pipeline jobs trigger simultaneously — each one's **Path Check** stage independently decides whether to build or skip.

### 5a — Expose Local Jenkins to the Internet

Jenkins on `localhost` is unreachable by GitHub. Use **ngrok** for a temporary public URL during development.

```bash
brew install ngrok
ngrok http 8090
```

ngrok prints a URL like `https://a1b2c3d4.ngrok-free.app`. Copy it.

> For a permanent URL without ngrok: move Jenkins to EC2 (Part 9).

### 5b — Add Webhook in GitHub

1. GitHub repo → **Settings → Webhooks → Add webhook**
2. Fill in:
   - **Payload URL**: `https://a1b2c3d4.ngrok-free.app/github-webhook/`
     > `/github-webhook/` is required — Jenkins listens on this exact path
   - **Content type**: `application/json`
   - **Which events**: Just the push event
3. **Add webhook** — GitHub sends a ping immediately. A green tick confirms Jenkins received it.

### 5c — Test

```bash
git commit --allow-empty -m "test: trigger Jenkins webhook"
git push origin main
```

All 6 jobs trigger. Those with no changes in their service folder complete in ~5 seconds (Path Check skips them).

---

## Part 6 — How the Pipeline Works (api-gateway example)

This is the core of the guide. Understanding this section means understanding the entire CI/CD flow.

### Pipeline overview

Every push to `main` runs this pipeline for api-gateway:

```
Path Check
    ↓ (only if api-gateway/ changed)
Docker Build & Push  →  image lands in ECR
    ↓
Deploy to Local      →  ArgoCD on Docker Desktop K8s syncs automatically
    ↓
Stage Approval       →  pipeline pauses, waits for your manual click (24h timeout)
    ↓
Deploy to Stage      →  ArgoCD on EKS stage syncs automatically
    ↓
Prod Approval        →  pipeline pauses, waits for your manual click (48h timeout)
    ↓
Deploy to Prod       →  ArgoCD on EKS prod syncs automatically
```

---

### Stage 1: Path Check

**Why it exists:** This is a monorepo — all 6 services live in one git repo. Every push to `main` triggers all 6 pipeline jobs simultaneously. Without this gate, a change to `catalog-service/` would also rebuild and redeploy `api-gateway` unnecessarily. Path Check prevents that.

**What it does:**

```bash
git diff --name-only HEAD~1 HEAD | grep '^api-gateway/' | wc -l
```

If the count is `0` → sets `SHOULD_BUILD=false` → every downstream stage is skipped.
If the count is `> 0` → sets `SHOULD_BUILD=true` → pipeline continues.

It also sets the image tag from the git commit SHA:
```groovy
env.IMAGE_TAG = "sha-${env.GIT_COMMIT.take(7)}"
// Result: sha-a1b2c3d
```

**Infinite loop guard:** When Jenkins deploys to any environment, it commits a change to the Helm values file in git (see Stage 3). That commit triggers the GitHub webhook again, which would start a new pipeline run. Without a guard this loops forever. Path Check detects Jenkins' own commits and stops:

```groovy
def committer = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
if (committer == 'Jenkins CI') {
    env.SHOULD_BUILD = 'false'  // Jenkins' own commit — stop here
}
```

```
Without guard:                        With guard:
Developer pushes code                 Developer pushes code
  → Jenkins builds          ✅          → Jenkins builds          ✅
    → Jenkins commits Helm                → Jenkins commits Helm
      → Jenkins builds again  ♻️            → Path Check sees committer = "Jenkins CI"
        → Jenkins commits Helm               → SHOULD_BUILD = false   🛑
          → infinite loop                    → pipeline ends cleanly
```

---

### Stage 2: Docker Build & Push

**Why it exists:** Produces the single immutable artifact that travels through all three environments. The image is built exactly once — local, stage, and prod all pull the same image. This guarantees what you tested locally is exactly what runs in production.

**The command that runs:**
```bash
aws ecr get-login-password --region us-east-1 \
    | docker login --username AWS --password-stdin 067744548987.dkr.ecr.us-east-1.amazonaws.com

docker build --platform linux/amd64 \
    -t 067744548987.dkr.ecr.us-east-1.amazonaws.com/retailstore/gateway:sha-a1b2c3d \
    api-gateway

docker push 067744548987.dkr.ecr.us-east-1.amazonaws.com/retailstore/gateway:sha-a1b2c3d
```

**`--platform linux/amd64` explained:** Jenkins runs on your Mac (Apple Silicon = ARM). Without this flag the image is built for ARM and fails to run on EKS nodes which are x86_64. This flag forces the correct architecture regardless of where the build runs.

---

#### Where do the environment variables come from?

Every `${env.X}` in the Jenkinsfile is resolved by Jenkins before handing the string to the shell. Three sources:

**Source 1 — Hardcoded in the `environment {}` block (top of Jenkinsfile):**

```groovy
environment {
    AWS_REGION   = 'us-east-1'
    SERVICE_DIR  = 'api-gateway'
    SERVICE_NAME = 'gateway'
    ECR_REPO     = 'retailstore/gateway'
    ...
}
```

These are plain strings you typed. Jenkins substitutes them directly — no lookup needed.

**Source 2 — Jenkins Credential Store:**

```groovy
ECR_REGISTRY = credentials('ecr-registry')
GITHUB_TOKEN = credentials('github-token')
```

`credentials('ecr-registry')` tells Jenkins: go to the encrypted credential store, find the secret with ID `ecr-registry`, and inject its value as an environment variable at runtime. The actual value (`067744548987.dkr.ecr.us-east-1.amazonaws.com`) never appears in the Jenkinsfile or git. Jenkins also masks it in console logs — it shows as `****` if accidentally printed.

**Source 3 — Set dynamically in Path Check stage:**

```groovy
env.IMAGE_TAG = "sha-${env.GIT_COMMIT.take(7)}"
```

`GIT_COMMIT` is a built-in Jenkins variable — Jenkins populates it automatically when it checks out the code. Path Check reads the first 7 characters and stores the result in `env.IMAGE_TAG`. Because it is assigned to `env.`, it persists across all subsequent stages in the same pipeline run.

---

#### Why `--username AWS`?

`AWS` is **not** your IAM username, your account name, or anything you configure. It is a **fixed literal string that Amazon ECR always requires** — every company, every team, every AWS account worldwide uses `--username AWS`.

ECR authentication works in two steps:
1. `aws ecr get-login-password` — calls the AWS API using your IAM credentials (access key or EC2 instance role) and returns a **short-lived token** valid for 12 hours.
2. `docker login --username AWS --password-stdin` — Docker sends this token to ECR. ECR ignores the username field entirely; it only validates the token. `AWS` is simply a required placeholder Amazon chose.

---

### Stages 3, 5, 7: Deploy to Local / Stage / Prod

**Why this pattern (GitOps):** Jenkins does not run `kubectl` or `helm upgrade` directly against the cluster. Instead it updates one line in a Helm values file in git and commits it. ArgoCD — running inside each cluster — watches that file and automatically reconciles the cluster to match. Git becomes the single source of truth for what is deployed where.

**What Jenkins changes (one line only):**

```yaml
# helm/local/gateway.yaml — before
image:
  tag: "sha-abc1234"

# helm/local/gateway.yaml — after Jenkins commit
image:
  tag: "sha-def5678"   ← only this line changes
```

**What Jenkins does:**

```bash
# 1. Patch the tag in the Helm values file
sed -i 's|^  tag: .*|  tag: "sha-def5678"|' retailstore-platform/helm/local/gateway.yaml

# 2. Commit and push that single change
git config user.name "Jenkins CI"
git add retailstore-platform/helm/local/gateway.yaml
git commit -m "ci(gateway): deploy sha-def5678 to local"
git push origin HEAD:main
```

**What ArgoCD does next (automatically):**
- Detects the Helm values file changed (polls every 3 min or via webhook)
- Runs `helm upgrade` on the target cluster with the new image tag
- Cluster pulls the new image from ECR and performs a zero-downtime rolling update

Each ArgoCD instance watches a different path — they never interfere with each other:

| ArgoCD instance | Watches path | Target cluster |
|---|---|---|
| ArgoCD on Docker Desktop | `helm/local/` | Local Docker Desktop K8s |
| ArgoCD on EKS stage | `helm/stage/` | AWS EKS stage |
| ArgoCD on EKS prod | `helm/prod/` | AWS EKS prod |

**Local deployment via ArgoCD:** ArgoCD runs identically on Docker Desktop K8s — install it once with `kubectl apply` and it works the same as on EKS. The only extra step for local: Docker Desktop K8s needs an ECR image pull secret since it has no IAM role (unlike EKS nodes). A small CronJob refreshes this secret every 6 hours because ECR tokens expire after 12 hours.

---

### Stages 4, 6: Manual Approval Gates

**Why they exist:** Stage is a shared AWS environment and prod carries real customer traffic. A human confirms the previous environment is healthy before promoting the image forward.

```groovy
stage('Stage Approval') {
    steps {
        timeout(time: 24, unit: 'HOURS') {
            input message: "Local looks good — promote gateway:sha-def5678 to Stage?",
                  ok: 'Deploy to Stage'
        }
    }
}
```

The pipeline **pauses** and shows an approval prompt in the Jenkins UI with **Deploy to Stage** and **Abort** buttons. If nobody approves within the timeout, the pipeline aborts — nothing is deployed.

| Gate | Timeout | Who approves |
|---|---|---|
| Stage Approval | 24 hours | Tech lead / QA after checking local |
| Prod Approval | 48 hours | Product owner / senior engineer after stage soak |

---

## Part 7 — Monitor Pipeline Runs

- Dashboard → click a job → **Build History** (left sidebar)
- Click a build number → **Console Output** for full logs
- Stages with `SHOULD_BUILD=false` appear grey (skipped) and complete in ~5 seconds
- Paused pipelines waiting for approval show an orange input icon

---

## Part 8 — Future Stages to Add

### Unit Tests + Code Coverage

Insert between **Path Check** and **Docker Build & Push**:

```groovy
stage('Unit Tests') {
    when { environment name: 'SHOULD_BUILD', value: 'true' }
    steps {
        dir(env.SERVICE_DIR) { sh 'mvn -B test' }
    }
    post {
        always {
            junit allowEmptyResults: true,
                  testResults: "${env.SERVICE_DIR}/target/surefire-reports/*.xml"
        }
    }
}

stage('Code Coverage') {
    when { environment name: 'SHOULD_BUILD', value: 'true' }
    steps {
        jacoco(
            execPattern:           "${env.SERVICE_DIR}/target/jacoco.exec",
            classPattern:          "${env.SERVICE_DIR}/target/classes",
            sourcePattern:         "${env.SERVICE_DIR}/src/main/java",
            minimumLineCoverage:   '50',
            minimumBranchCoverage: '50'
        )
    }
}
```

> `jacoco-maven-plugin 0.8.12` is already in all 6 `pom.xml` files — no pom changes needed.

### Veracode Security Scan

1. Download `Veracode.hpi` from the Veracode portal (not in the public Jenkins plugin registry)
2. Jenkins → Manage Jenkins → Plugins → Advanced → Upload Plugin
3. Add credentials: `veracode-api-id`, `veracode-api-key`
4. Add global env var `VERACODE_ENABLED = true` (Manage Jenkins → Configure System → Global properties)
5. Insert between Code Coverage and Docker Build & Push:

```groovy
stage('Veracode Scan') {
    when {
        allOf {
            environment name: 'SHOULD_BUILD',     value: 'true'
            environment name: 'VERACODE_ENABLED', value: 'true'
        }
    }
    steps {
        dir(env.SERVICE_DIR) { sh 'mvn -B -q package -DskipTests' }
        withCredentials([
            string(credentialsId: 'veracode-api-id',  variable: 'VID'),
            string(credentialsId: 'veracode-api-key', variable: 'VKEY')
        ]) {
            veracode applicationName: "retailstore-${env.SERVICE_NAME}",
                     criticality: 'VeryHigh',
                     useIDkey: true,
                     vid: "${VID}", vkey: "${VKEY}",
                     uploadIncludesPattern: "${env.SERVICE_DIR}/target/*.jar"
        }
    }
}
```

---

## Part 9 — EC2 Jenkins (EDP Team / Production)

> In a real enterprise this is done by the EDP / Platform team. Included here for learning.

EC2 Jenkins has two advantages over local:
- GitHub webhooks work directly — Elastic IP gives a permanent public URL, no ngrok
- IAM Role on EC2 handles all AWS auth — no credentials to manage

**Files:** `retailstore-infra/terraform/jenkins/`

### Configure your values

Edit `retailstore-infra/terraform/jenkins/terraform.tfvars`:

```hcl
aws_region       = "us-east-1"
aws_account_id   = "067744548987"
vpc_id           = "vpc-xxxxxxxxxxxxxxxxx"
subnet_id        = "subnet-xxxxxxxxxxxxxxx"  # must be a PUBLIC subnet
key_pair_name    = "retailstore-jenkins"
instance_type    = "t3.medium"
allowed_ssh_cidr = "x.x.x.x/32"            # your IP only — never 0.0.0.0/0
```

### Apply

```bash
cd retailstore-infra/terraform/jenkins
terraform init
terraform plan
terraform apply
```

### What Terraform provisions

| Resource | Detail |
|---|---|
| EC2 t3.medium | Amazon Linux 2023, bootstraps Java 21 + Jenkins LTS + Docker + AWS CLI + Maven |
| EBS 30 GB gp3 (encrypted) | Mounted as `/var/jenkins_home` — data survives EC2 replacement |
| Elastic IP | Stable public IP — permanent even if EC2 is stopped/started |
| IAM Role | Attached to EC2 — grants ECR push with no static credentials |
| Security Group | Port 8080 (UI), 50000 (JNLP agents), 22 (SSH from your IP only) |

### After apply

1. Wait ~3 min for bootstrap to finish
2. SSH in: `ssh -i ~/.ssh/retailstore-jenkins.pem ec2-user@<elastic-ip>`
3. Get password: `sudo cat /var/jenkins_home/secrets/initialAdminPassword`
4. Open `http://<elastic-ip>:8080` → complete wizard (same as Part 2)
5. Add credentials (Part 3) — skip 3c, IAM role handles AWS auth
6. Create 6 pipeline jobs (Part 4)
7. Update GitHub webhook to `http://<elastic-ip>:8080/github-webhook/`

---

## Quick Reference

| Task | Command / Location |
|---|---|
| Start local Jenkins | `cd retailstore-infra && ./scripts/start-jenkins.sh` |
| Stop local Jenkins | `cd retailstore-infra && ./scripts/stop-jenkins.sh` |
| Local Jenkins UI | http://localhost:8090 |
| Get admin password (local) | `docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword` |
| Stream Jenkins logs | `docker logs -f jenkins` |
| Wipe local Jenkins data | `docker volume rm jenkins_home` then start again |
| Credential IDs in Jenkinsfiles | `ecr-registry`, `github-token` |
| Webhook URL (local + ngrok) | `https://<ngrok-url>/github-webhook/` |
| Webhook URL (EC2) | `http://<elastic-ip>:8080/github-webhook/` |
| ECR username (always) | `AWS` — fixed literal, not your IAM username |
| Image tag format | `sha-a1b2c3d` — first 7 chars of git commit SHA |
| Approval timeout — stage | 24 hours |
| Approval timeout — prod | 48 hours |
