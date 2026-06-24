variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "aws_account_id" {
  description = "AWS account ID"
  type        = string
}

variable "catalog_db_password" {
  description = "Master password for catalog-db RDS instance"
  type        = string
  sensitive   = true
}

variable "orders_db_password" {
  description = "Master password for orders-db RDS instance"
  type        = string
  sensitive   = true
}
