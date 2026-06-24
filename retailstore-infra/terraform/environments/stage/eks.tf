module "eks" {
  source = "../../modules/eks"

  cluster_name    = "retailstore-stage"
  cluster_version = "1.30"

  vpc_id             = module.vpc.vpc_id
  public_subnet_ids  = module.vpc.public_subnet_ids
  private_subnet_ids = module.vpc.private_subnet_ids

  node_instance_types = ["t3.medium"]
  node_min_size       = 2
  node_max_size       = 4
  node_desired_size   = 2

  tags = {
    Environment = "stage"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

output "stage_eks_cluster_name" {
  value = module.eks.cluster_name
}

output "stage_eks_cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "stage_oidc_provider_arn" {
  value = module.eks.oidc_provider_arn
}
