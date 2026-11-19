package br.com.conectaPro.util;

/** Nenhum nível de busca devolveu uma coordenada confiável para o endereço. */
public class GeocodingException extends RuntimeException {

  public GeocodingException(String message) {
    super(message);
  }
}
