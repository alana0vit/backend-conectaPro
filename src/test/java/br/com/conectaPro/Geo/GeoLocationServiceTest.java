package br.com.conectaPro.Geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.conectaPro.dto.CoordinatesDTO;
import br.com.conectaPro.model.user.AddressUser;
import br.com.conectaPro.util.GeoLocationService;
import br.com.conectaPro.util.GeocodingException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class GeoLocationServiceTest {

  @Mock private RestTemplate restTemplate;
  private GeoLocationService service;

  @BeforeEach
  void setUp() {
    service = new GeoLocationService(restTemplate);
    ReflectionTestUtils.setField(service, "url", "https://api.geoapify.com/v1/geocode/search");
    ReflectionTestUtils.setField(service, "apiKey", "chave-teste");
  }

  private AddressUser endereco() {
    return AddressUser.builder()
        .street("Rua Teste")
        .number("10")
        .neighborhood("Afogados")
        .city("Recife")
        .state("PE")
        .zipCode("50770-000")
        .build();
  }

  /** Resposta no formato da Geoapify com um único resultado. */
  private String resposta(double lat, double lon, String uf, String cidade) {
    return "{\"features\":[{\"properties\":{\"lat\":"
        + lat
        + ",\"lon\":"
        + lon
        + ",\"state_code\":\""
        + uf
        + "\",\"city\":\""
        + cidade
        + "\"}}]}";
  }

  private static final String VAZIA = "{\"features\":[]}";

  @Test
  @DisplayName("Resultado válido no primeiro nível é aceito")
  void aceitaResultadoValido() {
    when(restTemplate.getForObject(any(URI.class), eq(String.class)))
        .thenReturn(resposta(-8.07, -34.91, "PE", "Recife"));

    CoordinatesDTO c = service.getCoordinates(endereco());

    assertEquals("-8.07", c.getLat());
    assertEquals("-34.91", c.getLon());
    verify(restTemplate, times(1)).getForObject(any(URI.class), eq(String.class));
  }

  @Test
  @DisplayName("G-01: resultado em outro estado é descartado (caso Jaboatão → Salvador)")
  void rejeitaEstadoDivergente() {
    // todos os níveis devolvem Salvador (BA) para um endereço de PE
    when(restTemplate.getForObject(any(URI.class), eq(String.class)))
        .thenReturn(resposta(-12.91, -38.36, "BA", "Salvador"));

    assertThrows(GeocodingException.class, () -> service.getCoordinates(endereco()));
  }

  @Test
  @DisplayName("G-03: se o primeiro nível diverge, o próximo nível resolve")
  void cascataUsaProximoNivel() {
    when(restTemplate.getForObject(any(URI.class), eq(String.class)))
        .thenReturn(resposta(-12.91, -38.36, "BA", "Salvador"))
        .thenReturn(VAZIA)
        .thenReturn(resposta(-8.08, -34.92, "PE", "Recife"));

    CoordinatesDTO c = service.getCoordinates(endereco());

    assertEquals("-8.08", c.getLat());
    verify(restTemplate, times(3)).getForObject(any(URI.class), eq(String.class));
  }

  @Test
  @DisplayName("Coordenada fora do território brasileiro é recusada")
  void rejeitaForaDoBrasil() {
    when(restTemplate.getForObject(any(URI.class), eq(String.class)))
        .thenReturn(resposta(48.85, 2.35, "PE", "Recife")); // Paris, com estado "certo"

    assertThrows(GeocodingException.class, () -> service.getCoordinates(endereco()));
  }

  @Test
  @DisplayName("Falha de rede em um nível não impede os próximos")
  void falhaDeRedeNaoInterrompe() {
    when(restTemplate.getForObject(any(URI.class), eq(String.class)))
        .thenThrow(new RuntimeException("timeout"))
        .thenReturn(resposta(-8.07, -34.91, "PE", "Recife"));

    assertEquals("-8.07", service.getCoordinates(endereco()).getLat());
  }

  @Test
  @DisplayName("Campos nulos não viram a palavra \"null\" na consulta")
  void naoEscreveNullNaConsulta() {
    AddressUser a = endereco();
    a.setNumber(null);
    a.setZipCode(null);
    when(restTemplate.getForObject(any(URI.class), eq(String.class)))
        .thenReturn(resposta(-8.07, -34.91, "PE", "Recife"));

    service.getCoordinates(a);

    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    verify(restTemplate).getForObject(uri.capture(), eq(String.class));
    assertFalse(uri.getValue().toString().toLowerCase().contains("null"));
  }

  @Test
  @DisplayName("Estado por extenso e sigla são equivalentes; cidade divergente é recusada")
  void comparaEstadoECidade() {
    AddressUser porExtenso = endereco();
    porExtenso.setState("Pernambuco");
    when(restTemplate.getForObject(any(URI.class), eq(String.class)))
        .thenReturn(resposta(-8.07, -34.91, "PE", "Recife"));
    assertEquals("-8.07", service.getCoordinates(porExtenso).getLat());

    // mesmo estado, cidade diferente (ex.: "Centro" casou com outra cidade de PE)
    when(restTemplate.getForObject(any(URI.class), eq(String.class)))
        .thenReturn(resposta(-8.11, -35.01, "PE", "Jaboatão dos Guararapes"));
    assertThrows(GeocodingException.class, () -> service.getCoordinates(endereco()));
  }

  @Test
  @DisplayName("Nível do CEP só é tentado quando o CEP tem 8 dígitos")
  void montaNiveis() {
    List<?> comCep = ReflectionTestUtils.invokeMethod(service, "montarTentativas", endereco());
    AddressUser semCep = endereco();
    semCep.setZipCode("123");
    List<?> semCepList = ReflectionTestUtils.invokeMethod(service, "montarTentativas", semCep);

    assertEquals(4, comCep.size());
    assertEquals(3, semCepList.size());
    assertTrue(comCep.get(2).toString().contains("50770-000"));
  }
}
