module "redis" {
  source = "../../modules/elasticache"

  cluster_id         = "retailstore-prod-redis"
  node_type          = "cache.r6g.large"
  num_cache_clusters = 2

  vpc_id                        = module.vpc.vpc_id
  subnet_ids                    = module.vpc.private_subnet_ids
  eks_cluster_security_group_id = module.eks.cluster_security_group_id

  tags = {
    Environment = "prod"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

output "prod_redis_primary_endpoint" {
  description = "Set as SPRING_REDIS_HOST in helm/prod/{gateway,catalog,checkout}.yaml"
  value       = module.redis.primary_endpoint
}

output "prod_redis_reader_endpoint" {
  description = "Optional read endpoint for read-heavy services"
  value       = module.redis.reader_endpoint
}
