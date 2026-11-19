package br.com.conectaPro.util;

import br.com.conectaPro.dto.CoordinatesDTO;
import br.com.conectaPro.model.user.AddressUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Converte endereço em coordenadas (Geoapify) com busca em cascata e validação do resultado: uma
 * coordenada só é aceita se estiver no Brasil e no mesmo estado (e, quando a API informa, na mesma
 * cidade) do endereço cadastrado. Coordenada errada é pior do que nenhuma coordenada.
 */
@Service
public class GeoLocationService {

  private static final Logger log = LoggerFactory.getLogger(GeoLocationService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Limites do território brasileiro: barra inversão/sinal trocado de lat/lon.
  static final double LAT_MIN = -34.0;
  static final double LAT_MAX = 6.0;
  static final double LON_MIN = -74.0;
  static final double LON_MAX = -34.0;

  private static final Map<String, String> UF_POR_NOME =
      Map.ofEntries(
          Map.entry("acre", "AC"),
          Map.entry("alagoas", "AL"),
          Map.entry("amapa", "AP"),
          Map.entry("amazonas", "AM"),
          Map.entry("bahia", "BA"),
          Map.entry("ceara", "CE"),
          Map.entry("distrito federal", "DF"),
          Map.entry("espirito santo", "ES"),
          Map.entry("goias", "GO"),
          Map.entry("maranhao", "MA"),
          Map.entry("mato grosso", "MT"),
          Map.entry("mato grosso do sul", "MS"),
          Map.entry("minas gerais", "MG"),
          Map.entry("para", "PA"),
          Map.entry("paraiba", "PB"),
          Map.entry("parana", "PR"),
          Map.entry("pernambuco", "PE"),
          Map.entry("piaui", "PI"),
          Map.entry("rio de janeiro", "RJ"),
          Map.entry("rio grande do norte", "RN"),
          Map.entry("rio grande do sul", "RS"),
          Map.entry("rondonia", "RO"),
          Map.entry("roraima", "RR"),
          Map.entry("santa catarina", "SC"),
          Map.entry("sao paulo", "SP"),
          Map.entry("sergipe", "SE"),
          Map.entry("tocantins", "TO"));

  private final RestTemplate restTemplate;

  @Value("${geoapify.url}")
  private String url;

  @Value("${geoapify.key}")
  private String apiKey;

  public GeoLocationService(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /**
   * Tenta do mais preciso para o mais genérico e para na primeira resposta que passa na validação:
   * 1) endereço completo; 2) rua + bairro + cidade + estado; 3) CEP; 4) bairro + cidade + estado.
   *
   * @throws GeocodingException se nenhum nível devolver uma coordenada válida
   */
  public CoordinatesDTO getCoordinates(AddressUser address) {
    List<Tentativa> tentativas = montarTentativas(address);

    for (int i = 0; i < tentativas.size(); i++) {
      Tentativa tentativa = tentativas.get(i);
      try {
        CoordinatesDTO coords = buscar(tentativa, address);
        if (coords != null) {
          log.info("Geocodificação resolvida no nível {} ({})", i + 1, tentativa.nome());
          return coords;
        }
      } catch (Exception e) {
        // falha de rede/JSON em um nível não impede os próximos
        log.warn(
            "Geocodificação, nível {} ({}) falhou: {}", i + 1, tentativa.nome(), e.getMessage());
      }
    }
    throw new GeocodingException("Nenhuma coordenada confiável encontrada para o endereço");
  }

  // ---------------------------------------------------------------- consultas

  record Tentativa(String nome, String texto, String postcode) {}

  List<Tentativa> montarTentativas(AddressUser a) {
    List<Tentativa> lista = new ArrayList<>();

    lista.add(
        new Tentativa(
            "endereço completo",
            juntar(
                a.getStreet(),
                a.getNumber(),
                a.getNeighborhood(),
                a.getCity(),
                a.getState(),
                a.getZipCode(),
                "Brasil"),
            null));

    if (!vazio(a.getStreet())) {
      lista.add(
          new Tentativa(
              "rua + bairro + cidade + estado",
              juntar(a.getStreet(), a.getNeighborhood(), a.getCity(), a.getState(), "Brasil"),
              null));
    }

    String cep = somenteDigitos(a.getZipCode());
    if (cep.length() == 8) {
      lista.add(new Tentativa("CEP", null, cep.substring(0, 5) + "-" + cep.substring(5)));
    }

    lista.add(
        new Tentativa(
            "bairro + cidade + estado",
            juntar(a.getNeighborhood(), a.getCity(), a.getState(), "Brasil"),
            null));
    return lista;
  }

  private CoordinatesDTO buscar(Tentativa tentativa, AddressUser address) throws Exception {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromUriString(url)
            .queryParam("filter", "countrycode:br")
            .queryParam("lang", "pt")
            .queryParam("limit", 5)
            .queryParam("apiKey", apiKey);
    if (tentativa.postcode() != null) {
      uri.queryParam("postcode", tentativa.postcode()).queryParam("country", "Brazil");
    } else {
      uri.queryParam("text", tentativa.texto());
    }
    URI requestUri = uri.build().encode().toUri();

    String response = restTemplate.getForObject(requestUri, String.class);
    if (response == null) {
      return null;
    }
    JsonNode features = MAPPER.readTree(response).path("features");
    if (!features.isArray()) {
      return null;
    }

    // Aceita o primeiro candidato que passa na validação (descarta os divergentes)
    for (JsonNode feature : features) {
      JsonNode props = feature.path("properties");
      if (resultadoValido(props, address)) {
        CoordinatesDTO dto = new CoordinatesDTO();
        dto.setLat(props.path("lat").asText());
        dto.setLon(props.path("lon").asText());
        return dto;
      }
    }
    return null;
  }

  // --------------------------------------------------------------- validação

  boolean resultadoValido(JsonNode props, AddressUser address) {
    double lat = props.path("lat").asDouble(Double.NaN);
    double lon = props.path("lon").asDouble(Double.NaN);
    if (Double.isNaN(lat) || Double.isNaN(lon)) {
      return false;
    }
    if (lat < LAT_MIN || lat > LAT_MAX || lon < LON_MIN || lon > LON_MAX) {
      return false;
    }
    if (!estadoConfere(props, address.getState())) {
      return false;
    }
    return cidadeConfere(props, address.getCity());
  }

  private boolean estadoConfere(JsonNode props, String estadoInformado) {
    String esperado = uf(estadoInformado);
    if (esperado == null) {
      return true; // estado ausente/ilegível no cadastro: não há como comparar
    }
    String retornado = uf(props.path("state_code").asText(""));
    if (retornado == null) {
      retornado = uf(props.path("state").asText(""));
    }
    return esperado.equals(retornado);
  }

  private boolean cidadeConfere(JsonNode props, String cidadeInformada) {
    String esperada = normalizar(cidadeInformada);
    if (esperada.isEmpty()) {
      return true;
    }
    boolean algumaInformada = false;
    for (String campo : List.of("city", "town", "village", "municipality", "county")) {
      String valor = normalizar(props.path(campo).asText(""));
      if (valor.isEmpty()) {
        continue;
      }
      algumaInformada = true;
      if (valor.equals(esperada) || valor.contains(esperada) || esperada.contains(valor)) {
        return true;
      }
    }
    // se a API não informou cidade, o filtro de estado/território já foi o que dava para checar
    return !algumaInformada;
  }

  /** "PE", "pe" ou "Pernambuco" → "PE"; null se não reconhecer. */
  static String uf(String estado) {
    String n = normalizar(estado);
    if (n.isEmpty()) {
      return null;
    }
    if (n.length() == 2) {
      return n.toUpperCase();
    }
    return UF_POR_NOME.get(n);
  }

  // ----------------------------------------------------------------- helpers

  private static String juntar(String... partes) {
    List<String> uteis = new ArrayList<>();
    for (String p : partes) {
      if (!vazio(p)) {
        uteis.add(p.trim());
      }
    }
    return String.join(", ", uteis);
  }

  private static boolean vazio(String s) {
    return s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim());
  }

  private static String somenteDigitos(String s) {
    return s == null ? "" : s.replaceAll("\\D", "");
  }

  static String normalizar(String s) {
    if (s == null) {
      return "";
    }
    return Normalizer.normalize(s, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase()
        .trim();
  }
}
