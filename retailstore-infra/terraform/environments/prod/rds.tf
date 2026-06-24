module "catalog_db" {
  source = "../../modules/rds"

  identifier = "retailstore-prod-catalog-db"
  db_name    = "catalogdb"
  username   = "catalogadmin"
  password   = var.catalog_db_password

  instance_class    = "db.r6g.large"
  allocated_storage = 100
  multi_az          = true
  deletion_protection = true
  skip_final_snapshot = false

  vpc_id                        = module.vpc.vpc_id
  subnet_ids                    = module.vpc.private_subnet_ids
  eks_cluster_security_group_id = module.eks.cluster_security_group_id

  tags = {
    Environment = "prod"
    Project     = "retailstore"
    Service     = "catalog-service"
    ManagedBy   = "terraform"
  }
}

module "orders_db" {
  source = "../../modules/rds"

  identifier = "retailstore-prod-orders-db"
  db_name    = "ordersdb"
  username   = "ordersadmin"
  password   = var.orders_db_password

  instance_class    = "db.r6g.large"
  allocated_storage = 100
  multi_az          = true
  deletion_protection = true
  skip_final_snapshot = false

  vpc_id                        = module.vpc.vpc_id
  subnet_ids                    = module.vpc.private_subnet_ids
  eks_cluster_security_group_id = module.eks.cluster_security_group_id

  tags = {
    Environment = "prod"
    Project     = "retailstore"
    Service     = "order-service"
    ManagedBy   = "terraform"
  }
}

output "prod_catalog_db_endpoint" {
  description = "Set as SPRING_DATASOURCE_URL in helm/prod/catalog.yaml"
  value       = module.catalog_db.endpoint
}

output "prod_orders_db_endpoint" {
  description = "Set as SPRING_DATASOURCE_URL in helm/prod/orders.yaml"
  value       = module.orders_db.endpoint
}
