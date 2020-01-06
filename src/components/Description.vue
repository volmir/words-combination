<template>
    <div>

<h4>Значение слова</h4>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Введите слово, значение которого вы хотите узнать</span>
    </p>
    <form class="form-inline" method="get" action="/description">
        <div class="form-group">
            <input type="text" class="form-control form-control-lg margin-right" placeholder="" name="word" value="" maxlength="30" autocomplete="off">
        </div>
        <button class="btn btn-primary btn-lg" id="start_search" type="submit">Поиск</button>
    </form>
</div> 
        

    </div>
</template>

<script>
export default {
    
}
</script>