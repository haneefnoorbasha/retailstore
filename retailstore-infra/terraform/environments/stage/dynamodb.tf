module "carts_table" {
  source = "../../modules/dynamodb"

  table_name = "retailstore-stage-carts"

  tags = {
    Environment = "stage"
    Project     = "retailstore"
    Service     = "cart-service"
    ManagedBy   = "terraform"
  }
}

output "stage_carts_table_name" {
  description = "Set as DYNAMODB_TABLE_NAME in helm/stage/carts.yaml"
  value       = module.carts_table.table_name
}

output "stage_carts_table_arn" {
  description = "Grant IAM access to cart-service IRSA role using this ARN"
  value       = module.carts_table.table_arn
}
