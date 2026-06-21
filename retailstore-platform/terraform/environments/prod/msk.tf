# Prod — MSK Kafka (Multi-AZ, high-availability)
# 3 brokers, kafka.m5.large, spread across three private subnets in different AZs.
# Bootstrap servers injected into order-service Helm chart after apply:
#   KAFKA_BOOTSTRAP_SERVERS = module.msk.bootstrap_brokers_tls

module "msk" {
  source = "../../modules/msk"

  cluster_name           = "retailstore-prod-kafka"
  kafka_version          = "3.6.0"
  number_of_broker_nodes = 3
  instance_type          = "kafka.m5.large"
  ebs_volume_size        = 100

  # Replace: one private subnet per AZ (us-east-1a, 1b, 1c)
  subnet_ids = [
    "",  # e.g. subnet-aaaaaaaa (us-east-1a)
    "",  # e.g. subnet-bbbbbbbb (us-east-1b)
    "",  # e.g. subnet-cccccccc (us-east-1c)
  ]

  # Replace: security group that allows port 9094 from EKS node security group
  security_group_ids = [""]  # e.g. sg-xxxxxxxxxxxxxxxxx

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
