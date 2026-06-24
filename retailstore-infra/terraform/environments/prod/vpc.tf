module "vpc" {
  source = "../../modules/vpc"

  name         = "retailstore-prod"
  cidr_block   = "10.2.0.0/16"
  cluster_name = "retailstore-prod"

  azs = ["us-east-1a", "us-east-1b", "us-east-1c"]

  public_subnet_cidrs = [
    "10.2.0.0/24",
    "10.2.1.0/24",
    "10.2.2.0/24",
  ]

  private_subnet_cidrs = [
    "10.2.10.0/24",
    "10.2.11.0/24",
    "10.2.12.0/24",
  ]

  tags = {
    Environment = "prod"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

output "prod_vpc_id" {
  value = module.vpc.vpc_id
}

output "prod_private_subnet_ids" {
  value = module.vpc.private_subnet_ids
}
