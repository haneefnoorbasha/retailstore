module "carts_table" {
  source = "../../modules/dynamodb"

  table_name = "retailstore-prod-carts"

  tags = {
    Environment = "prod"
    Project     = "retailstore"
    Service     = "cart-service"
    ManagedBy   = "terraform"
  }
}

output "prod_carts_table_name" {
  description = "Set as DYNAMODB_TABLE_NAME in helm/prod/carts.yaml"
  value       = module.carts_table.table_name
}

output "prod_carts_table_arn" {
  description = "Grant IAM access to cart-service IRSA role using this ARN"
  value       = module.carts_table.table_arn
}
