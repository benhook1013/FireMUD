variable "kubeconfig" {
  description = "Path to kubeconfig for the target cluster"
  type        = string
}

variable "namespace" {
  description = "Kubernetes namespace"
  type        = string
  default     = "firemud"
}

variable "velero_provider" {
  description = "Velero object storage provider (e.g., aws, gcp)"
  type        = string
}

variable "velero_bucket" {
  description = "Velero backup bucket name"
  type        = string
}

variable "velero_bucket_prefix" {
  description = "Prefix inside the backup bucket"
  type        = string
  default     = "postgres"
}

variable "velero_credentials_secret" {
  description = "Existing Kubernetes secret containing Velero credentials"
  type        = string
  default     = "velero-creds"
}
