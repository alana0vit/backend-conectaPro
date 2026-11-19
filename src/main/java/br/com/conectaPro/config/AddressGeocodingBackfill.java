package br.com.conectaPro.config;

import br.com.conectaPro.dto.CoordinatesDTO;
import br.com.conectaPro.model.user.AddressUser;
import br.com.conectaPro.model.user.AddressUserRepository;
import br.com.conectaPro.util.GeoLocationService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Rotina administrativa de execução única (G-04): geocodifica de novo os endereços já gravados sem
 * coordenada, usando a lógica nova com validação e fallback. Fica DESLIGADA por padrão; para rodar,
 * suba a aplicação uma vez com {@code GEOCODING_BACKFILL_ENABLED=true} e depois remova.
 *
 * <p>Com {@code GEOCODING_BACKFILL_ALL=true} (junto) reprocessa TODOS os endereços (corrige os que
 * foram gravados com coordenada errada antes da validação existir). Nesse modo, se o endereço não
 * passar na validação, a coordenada antiga é removida: coordenada errada é pior que nenhuma.
 */
@Component
@ConditionalOnProperty(name = "geocoding.backfill.enabled", havingValue = "true")
public class AddressGeocodingBackfill implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AddressGeocodingBackfill.class);

  private final AddressUserRepository repository;
  private final GeoLocationService geoLocationService;

  @Value("${geocoding.backfill.all:false}")
  private boolean reprocessAll;

  /** Pausa entre consultas para respeitar o limite de requisições por segundo do plano grátis. */
  @Value("${geocoding.backfill.delay-ms:300}")
  private long delayMs;

  public AddressGeocodingBackfill(
      AddressUserRepository repository, GeoLocationService geoLocationService) {
    this.repository = repository;
    this.geoLocationService = geoLocationService;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    List<AddressUser> alvos =
        reprocessAll ? repository.findAll() : repository.findByLatitudeIsNullOrLongitudeIsNull();
    log.info(
        "Backfill de geocodificação: {} endereço(s) a processar (todos={})",
        alvos.size(),
        reprocessAll);

    int corrigidos = 0;
    int semResultado = 0;
    for (AddressUser address : alvos) {
      try {
        CoordinatesDTO coords = geoLocationService.getCoordinates(address);
        address.setLatitude(Double.parseDouble(coords.getLat()));
        address.setLongitude(Double.parseDouble(coords.getLon()));
        repository.save(address);
        corrigidos++;
      } catch (Exception e) {
        semResultado++;
        log.warn(
            "Backfill: endereço {} continua sem coordenada confiável ({}/{}): {}",
            address.getId(),
            address.getCity(),
            address.getState(),
            e.getMessage());
        if (reprocessAll && address.getLatitude() != null) {
          address.setLatitude(null);
          address.setLongitude(null);
          repository.save(address);
        }
      }
      Thread.sleep(delayMs);
    }
    log.info(
        "Backfill concluído: {} corrigido(s), {} sem resultado confiável",
        corrigidos,
        semResultado);
  }
}
