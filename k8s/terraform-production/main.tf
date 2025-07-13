terraform {
  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.23"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.10"
    }
  }
}

provider "kubernetes" {
  config_path = var.kubeconfig
}

provider "helm" {
  kubernetes {
    config_path = var.kubeconfig
  }
}

resource "helm_release" "postgresql" {
  name       = "firemud-postgresql"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "postgresql-ha"
  namespace  = var.namespace
  values     = [file("${path.module}/postgres-values.yaml")]
}

resource "helm_release" "redis" {
  name       = "firemud-redis"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "redis"
  namespace  = var.namespace
  values     = [file("${path.module}/redis-values.yaml")]
}

resource "helm_release" "velero" {
  name       = "velero"
  repository = "https://vmware-tanzu.github.io/helm-charts"
  chart      = "velero"
  namespace  = var.namespace
  set {
    name  = "configuration.provider"
    value = var.velero_provider
  }
  set {
    name  = "configuration.backupStorageLocation.name"
    value = var.velero_bucket
  }
}
