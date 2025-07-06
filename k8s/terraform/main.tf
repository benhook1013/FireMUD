terraform {
  required_providers {
    kind = {
      source  = "tehcyx/kind"
      version = "~> 0.19"
    }
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

provider "kind" {}

resource "kind_cluster" "firemud" {
  name       = var.cluster_name
  kind_config = <<EOT
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30080
        hostPort: 80
        protocol: TCP
EOT
}

provider "kubernetes" {
  config_path = kind_cluster.firemud.kubeconfig_path
}

resource "kubernetes_namespace" "firemud" {
  metadata {
    name = "firemud"
  }
}

resource "kubernetes_service_account" "admin" {
  metadata {
    name      = "firemud-admin"
    namespace = kubernetes_namespace.firemud.metadata[0].name
  }
}

resource "kubernetes_cluster_role_binding" "admin" {
  metadata {
    name = "firemud-admin-binding"
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = "cluster-admin"
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account.admin.metadata[0].name
    namespace = kubernetes_service_account.admin.metadata[0].namespace
  }
}

resource "helm_release" "redis" {
  count      = var.install_redis ? 1 : 0
  name       = "redis"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "redis"
  namespace  = kubernetes_namespace.firemud.metadata[0].name
  values     = [file("${path.module}/redis-values.yaml")]
}

output "kubeconfig" {
  value = kind_cluster.firemud.kubeconfig_path
}
