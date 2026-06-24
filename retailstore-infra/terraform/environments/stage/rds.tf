module "catalog_db" {
  source = "../../modules/rds"

  identifier = "retailstore-stage-catalog-db"
  db_name    = "catalogdb"
  username   = "catalogadmin"
  password   = var.catalog_db_password

  instance_class    = "db.t3.small"
  allocated_storage = 20
  multi_az          = false
  deletion_protection = false
  skip_final_snapshot = true

  vpc_id                        = module.vpc.vpc_id
  subnet_ids                    = module.vpc.private_subnet_ids
  eks_cluster_security_group_id = module.eks.cluster_security_group_id

  tags = {
    Environment = "stage"
    Project     = "retailstore"
    Service     = "catalog-service"
    ManagedBy   = "terraform"
  }
}

module "orders_db" {
  source = "../../modules/rds"

  identifier = "retailstore-stage-orders-db"
  db_name    = "ordersdb"
  username   = "ordersadmin"
  password   = var.orders_db_password

  instance_class    = "db.t3.small"
  allocated_storage = 20
  multi_az          = false
  deletion_protection = false
  skip_final_snapshot = true

  vpc_id                        = module.vpc.vpc_id
  subnet_ids                    = module.vpc.private_subnet_ids
  eks_cluster_security_group_id = module.eks.cluster_security_group_id

  tags = {
    Environment = "stage"
    Project     = "retailstore"
    Service     = "order-service"
    ManagedBy   = "terraform"
  }
}

output "stage_catalog_db_endpoint" {
  description = "Set as SPRING_DATASOURCE_URL in helm/stage/catalog.yaml"
  value       = module.catalog_db.endpoint
}

output "stage_orders_db_endpoint" {
  description = "Set as SPRING_DATASOURCE_URL in helm/stage/orders.yaml"
  value       = module.orders_db.endpoint
}
