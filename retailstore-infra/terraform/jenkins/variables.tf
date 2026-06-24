variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "aws_account_id" {
  description = "AWS account ID"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID where Jenkins EC2 will be launched"
  type        = string
}

variable "subnet_id" {
  description = "Public subnet ID for Jenkins EC2 (must have internet access for GitHub webhooks)"
  type        = string
}

variable "key_pair_name" {
  description = "EC2 key pair name for SSH access (must already exist in AWS)"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type for Jenkins"
  type        = string
  default     = "t3.medium"   # 2 vCPU, 4 GB RAM — minimum for Jenkins
}

variable "jenkins_port" {
  description = "Port Jenkins listens on"
  type        = number
  default     = 8080
}

variable "allowed_ssh_cidr" {
  description = "CIDR block allowed to SSH into Jenkins (restrict to your office/VPN IP)"
  type        = string
  default     = "0.0.0.0/0"   # replace with your IP for production: e.g. "203.0.113.0/32"
}
