package io.scent.account;

import io.scent.common.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AddressService {
    private final AddressMapper mapper;
    private final AuthMapper accounts;
    public AddressService(AddressMapper mapper, AuthMapper accounts) { this.mapper = mapper; this.accounts = accounts; }

    public List<Address> list(long owner) { return mapper.list(owner); }

    @Transactional
    public Address save(long owner, Long id, AddressController.Input input) {
        accounts.lock(owner);
        Address old = id == null ? null : mapper.find(id, owner);
        if (id != null && old == null) { throw BusinessException.missing(); }
        List<Address> existing = mapper.list(owner);
        if (id == null && existing.size() >= 20) { throw BusinessException.invalid("地址最多保存20条"); }
        boolean isDefault = input.isDefault() || existing.isEmpty() || (old != null && old.getIsDefault());
        if (isDefault) { mapper.clearDefault(owner); }
        Address row = new Address();
        row.setId(id); row.setAccountId(owner); row.setReceiver(input.receiver().strip());
        row.setPhone(input.phone()); row.setRegion(input.region().strip()); row.setDetail(input.detail().strip());
        row.setIsDefault(isDefault);
        if (id == null) { mapper.create(row); } else { mapper.update(row); }
        return row;
    }

    @Transactional
    public void delete(long owner, long id) {
        accounts.lock(owner);
        Address old = mapper.find(id, owner);
        if (old == null) { throw BusinessException.missing(); }
        mapper.delete(id, owner);
        if (old.getIsDefault()) {
            List<Address> remaining = mapper.list(owner);
            if (!remaining.isEmpty()) { mapper.setDefault(remaining.getFirst().getId(), owner); }
        }
    }
}
