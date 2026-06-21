# Stage — MSK Kafka (single-AZ, cost-optimised)
# 2 brokers, kafka.t3.small, placed in two private subnets.
# Bootstrap servers injected into order-service Helm chart after apply:
#   KAFKA_BOOTSTRAP_SERVERS = module.msk.bootstrap_brokers_tls

module "msk" {
  source = "../../modules/msk"

  cluster_name           = "retailstore-stage-kafka"
  kafka_version          = "3.6.0"
  number_of_broker_nodes = 2
  instance_type          = "kafka.t3.small"
  ebs_volume_size        = 20

  # Replace: private subnet IDs from the stage VPC (one per broker)
  subnet_ids = [
    "",  # e.g. subnet-aaaaaaaa
    "",  # e.g. subnet-bbbbbbbb
  ]

  # Replace: security group that allows port 9094 from EKS node security group
  security_group_ids = [""]  # e.g. sg-xxxxxxxxxxxxxxxxx

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
