variable "cluster_name" {
  description = "MSK cluster name"
  type        = string
}

variable "kafka_version" {
  description = "Apache Kafka version"
  type        = string
  default     = "3.6.0"
}

variable "number_of_broker_nodes" {
  description = "Number of broker nodes (must equal number of subnet_ids for Multi-AZ)"
  type        = number
}

variable "instance_type" {
  description = "MSK broker instance type (e.g. kafka.t3.small, kafka.m5.large)"
  type        = string
}

variable "ebs_volume_size" {
  description = "EBS volume size in GiB per broker"
  type        = number
  default     = 20
}

variable "subnet_ids" {
  description = "Subnet IDs for broker placement (one per broker node)"
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security group IDs attached to broker nodes"
  type        = list(string)
}

variable "replication_factor" {
  description = "Default replication factor for topics"
  type        = number
  default     = 2
}

variable "min_insync_replicas" {
  description = "Minimum in-sync replicas before a write is accepted"
  type        = number
  default     = 1
}

variable "tags" {
  description = "Tags applied to all resources"
  type        = map(string)
  default     = {}
}
