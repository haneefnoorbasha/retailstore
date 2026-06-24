# ECR Setup Guide

Amazon Elastic Container Registry (ECR) is the private Docker registry where Jenkins pushes service images and EKS pulls them from.

---

## ECR Structure

ECR has two concepts — do not confuse them:

| Concept | What it is | Who creates it |
|---|---|---|
| **Registry** | One per AWS account. Shared by all services. Auto-exists. | Nobody — AWS creates it automatically |
| **Repository** | One per service image. A named path inside the registry. | Created via Terraform |

```
123456789012.dkr.ecr.us-east-1.amazonaws.com   ← Registry (1, auto-exists)
    └── retailstore/catalog                      ← Repository (Terraform)
    └── retailstore/carts                        ← Repository (Terraform)
    └── retailstore/checkout                     ← Repository (Terraform)
    └── retailstore/orders                       ← Repository (Terraform)
    └── retailstore/experience                   ← Repository (Terraform)
    └── retailstore/gateway                      ← Repository (Terraform)
```

The `ecr-registry` credential in Jenkins is the **registry URI prefix** only:
```
123456789012.dkr.ecr.us-east-1.amazonaws.com
```
Jenkins appends `/retailstore/catalog`, `/retailstore/carts`, etc. per service in the Jenkinsfile.

---

## ECR Pricing

ECR charges by **storage (GB/month)** — not per repository.

| What | Cost |
|---|---|
| Storage | $0.10 per GB per month |
| Docker push (data in) | Free |
| Pull from EKS in same region | Free |

**Estimate for this project:**
Each Spring Boot Docker image is ~150–200 MB. With the lifecycle policy keeping the last 10 images per repo:
```
6 repos × 10 images × ~180 MB = ~10.8 GB → ~$1.08/month
```
The lifecycle policy defined in Terraform handles this automatically — old images expire without manual cleanup.

---

## Prerequisites

- AWS account with admin or sufficient IAM permissions
- AWS CLI installed and configured
- Terraform >= 1.7 installed

```bash
# Verify AWS CLI is configured
aws sts get-caller-identity
```

Expected output:
```json
{
    "Account": "123456789012",
    "Arn": "arn:aws:iam::123456789012:user/your-username"
}
```

Note your **Account ID** — you will need it in `terraform.tfvars`.

---

## One-Time S3 State Bucket Setup

Before the very first `terraform init`, create the S3 bucket that stores Terraform state. Run this **once only** — the bucket is shared by all Terraform modules (ECR, Jenkins, environments/stage, environments/prod):

```bash
# Include your account ID in the bucket name — S3 names are globally unique
# and generic names like "retailstore-terraform-state" may already be taken
# by another AWS account.
aws s3api create-bucket \
    --bucket retailstore-terraform-state-<YOUR_ACCOUNT_ID> \
    --region us-east-1

aws s3api put-bucket-versioning \
    --bucket retailstore-terraform-state-<YOUR_ACCOUNT_ID> \
    --versioning-configuration Status=Enabled
```

For this project the bucket is: `retailstore-terraform-state-067744548987`

---

## Create ECR Repositories

**Files:** `retailstore-platform/terraform/ecr/`

### 1. Set your account ID

Edit `retailstore-platform/terraform/ecr/terraform.tfvars`:

```hcl
aws_region     = "us-east-1"
aws_account_id = "123456789012"   # your actual AWS account ID
```

### 2. Apply

```bash
cd retailstore-platform/terraform/ecr

terraform init     # downloads AWS provider, connects to S3 backend
terraform plan     # preview what will be created
terraform apply    # creates all 6 repos + lifecycle policies
```

After `apply`, Terraform prints the outputs:

```
registry_uri = "123456789012.dkr.ecr.us-east-1.amazonaws.com"

repository_uris = {
  "catalog"    = "123456789012.dkr.ecr.us-east-1.amazonaws.com/retailstore/catalog"
  "carts"      = "123456789012.dkr.ecr.us-east-1.amazonaws.com/retailstore/carts"
  "checkout"   = "123456789012.dkr.ecr.us-east-1.amazonaws.com/retailstore/checkout"
  "orders"     = "123456789012.dkr.ecr.us-east-1.amazonaws.com/retailstore/orders"
  "experience" = "123456789012.dkr.ecr.us-east-1.amazonaws.com/retailstore/experience"
  "gateway"    = "123456789012.dkr.ecr.us-east-1.amazonaws.com/retailstore/gateway"
}
```

Copy the `registry_uri` value — paste it as the `ecr-registry` credential in Jenkins (see `jenkins-setup.md`).

---

## Day-2 Operations

### Add a new service

Edit `main.tf` — add the service name to `locals.services`:

```hcl
locals {
  services = ["catalog", "carts", "checkout", "orders", "experience", "gateway", "notifications"]
}
```

Then run `terraform apply` — one new repo + lifecycle policy created, nothing else touched.

### Change the lifecycle policy (e.g. keep 20 images instead of 10)

Edit `countNumber = 10` → `20` in `main.tf`, commit, `terraform apply`. Full audit trail in Git.

### Verify repositories exist

```bash
aws ecr describe-repositories --region us-east-1 \
  --query 'repositories[].repositoryName' --output table
```

---

## IAM Permissions for Jenkins

Jenkins needs ECR push permissions. How it gets them depends on where Jenkins runs:

| Jenkins location | How credentials work |
|---|---|
| **Local Mac (Docker container)** | Create IAM user with Access Key → add to Jenkins credential store (see `jenkins-setup.md` Part 3c) |
| **EC2 (Terraform-provisioned)** | IAM Role is attached to the EC2 via Terraform — no credentials to manage. AWS SDK picks up the role automatically |

The EC2 IAM Role is already defined in `retailstore-platform/terraform/jenkins/main.tf` — it includes all necessary ECR push permissions.

---

## Smoke Test — Push a Test Image

Before running Jenkins, verify the setup works from your Mac:

```bash
AWS_REGION=us-east-1
ECR_REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com

aws ecr get-login-password --region $AWS_REGION \
  | docker login --username AWS --password-stdin $ECR_REGISTRY

docker tag hello-world:latest $ECR_REGISTRY/retailstore/catalog:test
docker push $ECR_REGISTRY/retailstore/catalog:test

# Clean up
aws ecr batch-delete-image \
  --repository-name retailstore/catalog \
  --image-ids imageTag=test \
  --region $AWS_REGION
```

---

## Quick Reference

| Item | Value |
|---|---|
| Terraform module | `retailstore-platform/terraform/ecr/` |
| S3 state key | `ecr/terraform.tfstate` |
| Registry URI format | `<account-id>.dkr.ecr.<region>.amazonaws.com` |
| Repos | `retailstore/catalog`, `carts`, `checkout`, `orders`, `experience`, `gateway` |
| Image tag format | `sha-a1b2c3d` (7-char git SHA, set by Jenkinsfile) |
| Lifecycle policy | Keep last 10 images per repo (~$1/month storage) |
| Jenkins credential ID | `ecr-registry` → registry URI (not the full repo path) |
| IAM for local Jenkins | IAM user with Access Key added to Jenkins credentials |
| IAM for EC2 Jenkins | IAM Role attached via Terraform — no manual credentials needed |
