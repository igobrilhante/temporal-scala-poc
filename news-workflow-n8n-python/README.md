# News Verification Workflow - n8n + Python

Implementação do workflow de verificação de notícias usando **n8n** para orquestração e **Python/FastAPI** para os serviços de backend.

## Visão Geral

Este projeto demonstra uma arquitetura de workflow low-code/no-code usando:

- **n8n**: Plataforma de automação visual para orquestração do workflow
- **FastAPI**: API REST de alta performance para os serviços de verificação
- **OpenAI**: Análise de conteúdo com IA (opcional)
- **Docker**: Containerização de todos os serviços

## Arquitetura

```
┌─────────────────────────────────────────────────────────────────────┐
│                         n8n Workflow Engine                          │
│  (Visual workflow orchestration, triggers, scheduling)              │
└─────────────────────────────────────────────────────────────────────┘
                               │
                               │ HTTP Requests
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     FastAPI Application                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────────┐  │
│  │   Scraper   │  │  AI Agent   │  │       Classifier            │  │
│  │   Service   │  │   Service   │  │        Service              │  │
│  │             │  │             │  │                             │  │
│  │ • RSS Feed  │  │ • Find      │  │ • Source credibility        │  │
│  │ • HTML      │  │   sources   │  │ • Linguistic analysis       │  │
│  │ • Content   │  │ • Extract   │  │ • Cross-reference           │  │
│  │   extract   │  │   claims    │  │ • AI classification         │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                               │
                               │ (Optional)
                               ▼
                    ┌─────────────────┐
                    │   OpenAI API    │
                    └─────────────────┘
```

## Pré-requisitos

- Docker & Docker Compose
- Python 3.11+ (para desenvolvimento local)
- OpenAI API Key (opcional, para análise com IA)

## Quick Start

### 1. Configurar Variáveis de Ambiente

```bash
cp .env.example .env
# Edite .env e adicione sua OPENAI_API_KEY (opcional)
```

### 2. Iniciar os Serviços

```bash
docker-compose up -d
```

Aguarde os serviços estarem prontos:

```bash
docker-compose ps
```

### 3. Acessar as Interfaces

- **n8n UI**: http://localhost:5678
  - Usuário: `admin`
  - Senha: `admin123`

- **FastAPI Docs**: http://localhost:8000/docs

### 4. Importar o Workflow no n8n

1. Acesse http://localhost:5678
2. Vá em **Workflows** > **Import from File**
3. Selecione `n8n/news_verification_workflow.json`
4. Clique em **Execute Workflow**

## Estrutura do Projeto

```
news-workflow-n8n-python/
├── app/                          # Aplicação Python/FastAPI
│   ├── __init__.py
│   ├── main.py                   # FastAPI app e endpoints
│   ├── models.py                 # Modelos Pydantic
│   ├── config.py                 # Configurações
│   └── services/
│       ├── __init__.py
│       ├── news_scraper.py       # Serviço de scraping
│       ├── ai_agent.py           # Serviço de IA
│       └── news_classifier.py    # Classificador
│
├── n8n/                          # Workflows n8n
│   ├── news_verification_workflow.json
│   └── webhook_trigger_workflow.json
│
├── docker-compose.yml            # Orquestração Docker
├── Dockerfile                    # Build da API Python
├── requirements.txt              # Dependências Python
├── .env.example                  # Template de variáveis
└── README.md
```

## Fluxo do Workflow

```
1. TRIGGER (Manual ou Webhook)
   │
2. FETCH HEADLINES ──────────────────────────────────┐
   │ POST /api/v1/scraper/headlines                  │
   │ • Detecta tipo de fonte (RSS/HTML)              │
   │ • Extrai headlines com URLs e resumos           │
   │                                                 │
3. FOR EACH HEADLINE:                                │
   │                                                 │
   ├─► EXTRACT CONTENT                               │
   │   POST /api/v1/scraper/content                  │
   │   • Extrai conteúdo completo do artigo          │
   │                                                 │
   ├─► FIND SOURCES (paralelo)                       │
   │   POST /api/v1/agent/sources                    │
   │   • Busca fontes alternativas                   │
   │   • Analisa se confirmam ou contradizem         │
   │                                                 │
   ├─► EXTRACT CLAIMS (paralelo)                     │
   │   POST /api/v1/agent/claims                     │
   │   • Identifica afirmações verificáveis          │
   │                                                 │
   └─► CLASSIFY NEWS                                 │
       POST /api/v1/classifier/classify              │
       • Analisa credibilidade da fonte              │
       • Analisa padrões linguísticos                │
       • Pondera fontes cruzadas                     │
       • Classificação por IA (opcional)             │
   │                                                 │
4. AGGREGATE RESULTS                                 │
   │ • Estatísticas consolidadas                     │
   │ • Relatório formatado                           │
   ▼                                                 │
   DONE ◄────────────────────────────────────────────┘
```

## API Endpoints

### Scraper

| Endpoint | Método | Descrição |
|----------|--------|-----------|
| `/api/v1/scraper/headlines` | POST | Busca headlines de uma fonte |
| `/api/v1/scraper/content` | POST | Extrai conteúdo de um artigo |

### AI Agent

| Endpoint | Método | Descrição |
|----------|--------|-----------|
| `/api/v1/agent/sources` | POST | Busca fontes alternativas |
| `/api/v1/agent/claims` | POST | Extrai afirmações verificáveis |

### Classifier

| Endpoint | Método | Descrição |
|----------|--------|-----------|
| `/api/v1/classifier/classify` | POST | Classifica uma notícia |

## Exemplo de Uso via API

### Buscar Headlines

```bash
curl -X POST http://localhost:8000/api/v1/scraper/headlines \
  -H "Content-Type: application/json" \
  -d '{
    "source_url": "https://feeds.bbci.co.uk/news/rss.xml",
    "max_headlines": 5
  }'
```

### Classificar Notícia

```bash
curl -X POST http://localhost:8000/api/v1/classifier/classify \
  -H "Content-Type: application/json" \
  -d '{
    "headline": "Breaking: Major announcement from government",
    "content": "The government announced today...",
    "source_domain": "bbc.co.uk",
    "alternative_sources": [],
    "claims": []
  }'
```

## Workflows Disponíveis

### 1. News Verification Workflow (Manual)

Workflow completo com trigger manual. Ideal para testes e análises pontuais.

**Arquivo**: `n8n/news_verification_workflow.json`

### 2. Webhook Trigger Workflow

Versão simplificada com webhook para integração com sistemas externos.

**Arquivo**: `n8n/webhook_trigger_workflow.json`

**Uso**:
```bash
curl -X POST http://localhost:5678/webhook/verify-news \
  -H "Content-Type: application/json" \
  -d '{
    "source_url": "https://feeds.bbci.co.uk/news/rss.xml",
    "max_headlines": 3
  }'
```

## Fatores de Classificação

| Fator | Peso | Descrição |
|-------|------|-----------|
| Credibilidade da Fonte | 25% | Análise do domínio (confiável vs. suspeito) |
| Análise Linguística | 20% | Detecção de clickbait, sensacionalismo |
| Referência Cruzada | 30% | Corroboração por outras fontes |
| Análise de IA | 25% | Classificação via GPT-4 (opcional) |

## Veredictos

- **LIKELY_REAL**: Múltiplas fontes confirmam, alta credibilidade
- **LIKELY_FAKE**: Padrões suspeitos, contradições significativas
- **UNCERTAIN**: Sinais mistos, requer investigação adicional

## Desenvolvimento Local

### Sem Docker

```bash
# Criar ambiente virtual
python -m venv venv
source venv/bin/activate  # Linux/Mac
# ou: venv\Scripts\activate  # Windows

# Instalar dependências
pip install -r requirements.txt

# Executar API
python -m app.main
```

### Testes

```bash
# Health check
curl http://localhost:8000/health

# Documentação interativa
open http://localhost:8000/docs
```

## Configuração Avançada

### Variáveis de Ambiente

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `OPENAI_API_KEY` | - | Chave da API OpenAI |
| `OPENAI_MODEL` | `gpt-4` | Modelo a usar |
| `API_HOST` | `0.0.0.0` | Host da API |
| `API_PORT` | `8000` | Porta da API |
| `LOG_LEVEL` | `INFO` | Nível de log |
| `N8N_BASIC_AUTH_USER` | `admin` | Usuário n8n |
| `N8N_BASIC_AUTH_PASSWORD` | `admin123` | Senha n8n |

### Fontes Confiáveis

A lista de fontes confiáveis pode ser configurada em `app/config.py`:

```python
reliable_sources = [
    "bbc.co.uk", "reuters.com", "apnews.com",
    "nytimes.com", "theguardian.com", ...
]
```

## Comparação: n8n vs Temporal

| Aspecto | n8n | Temporal |
|---------|-----|----------|
| **Paradigma** | Low-code visual | Code-first |
| **Curva de Aprendizado** | Baixa | Média-Alta |
| **Debugging** | Visual, intuitivo | Requer ferramentas |
| **Escalabilidade** | Moderada | Alta |
| **Durabilidade** | Básica | Avançada |
| **Integração** | 400+ conectores | Código customizado |
| **Deployment** | Simples | Complexo |

### Quando usar n8n

- Automações simples a moderadas
- Prototipagem rápida
- Equipes com menos desenvolvedores
- Integrações com muitos serviços externos

### Quando usar Temporal

- Workflows de longa duração
- Alta resiliência necessária
- Lógica de compensação complexa
- Aplicações enterprise críticas

## Troubleshooting

### n8n não conecta na API

```bash
# Verificar se a API está rodando
docker-compose logs news-api

# Testar conectividade
docker-compose exec n8n wget -qO- http://news-api:8000/health
```

### Erro de OpenAI

Sem a API key do OpenAI, o sistema funciona com análise heurística apenas.
Configure a variável `OPENAI_API_KEY` para habilitar análise com IA.

### Limpar dados

```bash
docker-compose down -v
```

## Licença

MIT
