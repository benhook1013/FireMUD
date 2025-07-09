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
