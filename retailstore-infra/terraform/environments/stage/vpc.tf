module "vpc" {
  source = "../../modules/vpc"

  name       = "retailstore-stage"
  cidr_block = "10.1.0.0/16"
  cluster_name = "retailstore-stage"

  azs = ["us-east-1a", "us-east-1b", "us-east-1c"]

  public_subnet_cidrs = [
    "10.1.0.0/24",
    "10.1.1.0/24",
    "10.1.2.0/24",
  ]

  private_subnet_cidrs = [
    "10.1.10.0/24",
    "10.1.11.0/24",
    "10.1.12.0/24",
  ]

  tags = {
    Environment = "stage"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

output "stage_vpc_id" {
  value = module.vpc.vpc_id
}

output "stage_private_subnet_ids" {
  value = module.vpc.private_subnet_ids
}
