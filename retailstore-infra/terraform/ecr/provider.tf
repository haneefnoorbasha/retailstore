terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Remote state in S3 — prevents two people running terraform apply at the same time
  # Create the S3 bucket manually once before running terraform init:
  #   aws s3api create-bucket --bucket retailstore-terraform-state --region us-east-1
  #   aws s3api put-bucket-versioning --bucket retailstore-terraform-state \
  #       --versioning-configuration Status=Enabled
  backend "s3" {
    bucket = "retailstore-terraform-state-067744548987"
    key    = "ecr/terraform.tfstate"
    region = "us-east-1"
  }
}

provider "aws" {
  region = var.aws_region
}
