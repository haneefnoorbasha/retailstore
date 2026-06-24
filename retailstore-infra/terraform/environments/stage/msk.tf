# Stage — MSK Kafka (single-AZ, cost-optimised)
# 2 brokers, kafka.t3.small, placed in two private subnets.

resource "aws_security_group" "msk" {
  name        = "retailstore-stage-msk-sg"
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
    Name        = "retailstore-stage-msk-sg"
    Environment = "stage"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

module "msk" {
  source = "../../modules/msk"

  cluster_name           = "retailstore-stage-kafka"
  kafka_version          = "3.6.0"
  number_of_broker_nodes = 2
  instance_type          = "kafka.t3.small"
  ebs_volume_size        = 20

  subnet_ids = slice(module.vpc.private_subnet_ids, 0, 2)

  security_group_ids = [aws_security_group.msk.id]

  replication_factor  = 2
  min_insync_replicas = 1

  tags = {
    Environment = "stage"
    Project     = "retailstore"
    ManagedBy   = "terraform"
  }
}

output "stage_kafka_bootstrap_servers" {
  description = "Set this as KAFKA_BOOTSTRAP_SERVERS in helm/stage/orders.yaml"
  value       = module.msk.bootstrap_brokers_tls
  sensitive   = false
}
