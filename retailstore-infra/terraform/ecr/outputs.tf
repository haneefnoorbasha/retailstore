output "registry_uri" {
  description = "ECR registry URI — paste this as the ecr-registry credential in Jenkins"
  value       = "${var.aws_account_id}.dkr.ecr.${var.aws_region}.amazonaws.com"
}

output "repository_uris" {
  description = "Full URI for each service repository"
  value       = { for k, v in aws_ecr_repository.services : k => v.repository_url }
}
