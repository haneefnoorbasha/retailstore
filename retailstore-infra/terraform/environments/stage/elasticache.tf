module "redis" {
  source = "../../modules/elasticache"

  cluster_id         = "retailstore-stage-redis"
  node_type          = "cache.t3.micro"
  num_cache_clusters = 1

  vpc_id                        = module.vpc.vpc_id
  subnet_ids                    = module.vpc.private_subnet_ids
  eks_cluster_security_group_id = module.eks.cluster_security_group_id

  tags = {
    Environment = "stage"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

output "stage_redis_primary_endpoint" {
  description = "Set as SPRING_REDIS_HOST in helm/stage/{gateway,catalog,checkout}.yaml"
  value       = module.redis.primary_endpoint
}
