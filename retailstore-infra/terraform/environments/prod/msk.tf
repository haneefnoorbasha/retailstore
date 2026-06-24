# Prod — MSK Kafka (Multi-AZ, high-availability)
# 3 brokers, kafka.m5.large, spread across three private subnets in different AZs.

resource "aws_security_group" "msk" {
  name        = "retailstore-prod-msk-sg"
  description = "Allow Kafka TLS from EKS nodes"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description     = "Kafka TLS from EKS nodes"
    from_port       = 9094
    to_port         = 9094
    protocol        = "tcp"
    security_groups = [module.eks.cluster_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "retailstore-prod-msk-sg"
    Environment = "prod"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

module "msk" {
  source = "../../modules/msk"

  cluster_name           = "retailstore-prod-kafka"
  kafka_version          = "3.6.0"
  number_of_broker_nodes = 3
  instance_type          = "kafka.m5.large"
  ebs_volume_size        = 100

  subnet_ids = module.vpc.private_subnet_ids

  security_group_ids = [aws_security_group.msk.id]

  replication_factor  = 3
  min_insync_replicas = 2

  tags = {
    Environment = "prod"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

output "prod_kafka_bootstrap_servers" {
  description = "Set this as KAFKA_BOOTSTRAP_SERVERS in helm/prod/orders.yaml"
  value       = module.msk.bootstrap_brokers_tls
  sensitive   = false
}
