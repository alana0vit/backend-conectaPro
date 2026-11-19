package br.com.conectaPro.model.user;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressUserRepository extends JpaRepository<AddressUser, Long> {

  /** Endereços que ficaram sem coordenada (G-04: reprocessamento). */
  java.util.List<AddressUser> findByLatitudeIsNullOrLongitudeIsNull();
}
