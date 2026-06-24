output "table_name" {
  description = "DynamoDB table name"
  value       = aws_dynamodb_table.this.name
}

output "table_arn" {
  description = "DynamoDB table ARN — use this to grant IAM access from EKS service accounts"
  value       = aws_dynamodb_table.this.arn
}
